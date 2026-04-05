package overhead;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Measures the wall-clock overhead added by the capture and replay agents.
 *
 * For each workload class, runs MEASURE rounds in three modes:
 *   1. Baseline  — plain JVM, no agent
 *   2. Capture   — with trace-capture-agent.jar attached
 *   3. Replay    — with trace-replay-agent.jar attached (reads the trace produced
 *                  by a dedicated capture run that precedes the replay measurements)
 *
 * Prints a summary table with mean ± std-dev in milliseconds and the
 * overhead ratio relative to the baseline.
 *
 * Usage (invoked by run.sh):
 *   java -cp overhead-bench.jar overhead.BenchmarkRunner \
 *        <capture-agent.jar> <replay-agent.jar> <overhead-bench.jar>
 */
public class BenchmarkRunner {

    static final String[] WORKLOADS = {
        "overhead.WorkloadPlainCounter",
        "overhead.WorkloadAtomicCounter",
        "overhead.WorkloadVolatileWrite",
        "overhead.WorkloadSharedObject",
        "overhead.WorkloadArrayElement",
    };

    static final int WARMUP  = 3;   // discarded warm-up rounds per mode
    static final int MEASURE = 10;  // timed rounds per mode
    static final int TIMEOUT = 120; // per-process timeout in seconds

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: BenchmarkRunner <capture-agent.jar> <replay-agent.jar> <overhead-bench.jar>");
            System.exit(1);
        }

        Path captureJar = Paths.get(args[0]).toAbsolutePath();
        Path replayJar  = Paths.get(args[1]).toAbsolutePath();
        Path benchJar   = Paths.get(args[2]).toAbsolutePath();

        checkExists(captureJar, "capture agent");
        checkExists(replayJar,  "replay agent");
        checkExists(benchJar,   "benchmark jar");

        // System.out.println("Capture agent : " + captureJar);
        // System.out.println("Replay agent  : " + replayJar);
        // System.out.println("Benchmark jar : " + benchJar);
        System.out.printf ("Warmup rounds : %d  |  Measure rounds: %d%n%n", WARMUP, MEASURE);

        Path workDir = Files.createTempDirectory("overhead-bench-");
        try {
            printHeader();
            for (String workload : WORKLOADS) {
                benchmarkWorkload(workload, captureJar, replayJar, benchJar, workDir);
            }
        } finally {
            deleteDir(workDir);
        }
    }

    // ── Per-workload benchmark ──────────────────────────────────────────────

    static void benchmarkWorkload(
            String cls, Path captureJar, Path replayJar, Path benchJar, Path workDir)
            throws Exception {

        List<String> baseCmd    = javaCmd(null,       benchJar, cls);
        List<String> captureCmd = javaCmd(captureJar, benchJar, cls);
        List<String> replayCmd  = javaCmd(replayJar,  benchJar, cls);

        // ── Baseline ──────────────────────────────────────────────────────
        for (int i = 0; i < WARMUP;   i++) run(baseCmd, workDir);
        long[] baseMs = measure(baseCmd, workDir);

        // ── Capture ───────────────────────────────────────────────────────
        for (int i = 0; i < WARMUP;   i++) run(captureCmd, workDir);
        long[] capMs  = measure(captureCmd, workDir);

        // Produce one clean trace.bin for the replay measurements below.
        run(captureCmd, workDir);

        // ── Replay ────────────────────────────────────────────────────────
        for (int i = 0; i < WARMUP;   i++) run(replayCmd, workDir);
        long[] repMs  = measure(replayCmd, workDir);

        // ── Print row ─────────────────────────────────────────────────────
        String name = cls.substring(cls.lastIndexOf('.') + 1);
        System.out.printf(
            "%-28s  %7.1f ±%6.1f  %7.1f ±%6.1f  %7.1f ±%6.1f  %6.2fx  %6.2fx%n",
            name,
            mean(baseMs), std(baseMs),
            mean(capMs),  std(capMs),
            mean(repMs),  std(repMs),
            mean(capMs) / mean(baseMs),
            mean(repMs) / mean(baseMs));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    static List<String> javaCmd(Path agentJar, Path benchJar, String mainClass) {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        if (agentJar != null) cmd.add("-javaagent:" + agentJar);
        cmd.add("-cp");
        cmd.add(benchJar.toString());
        cmd.add(mainClass);
        return cmd;
    }

    static long[] measure(List<String> cmd, Path workDir) throws Exception {
        long[] ms = new long[MEASURE];
        for (int i = 0; i < MEASURE; i++) ms[i] = run(cmd, workDir);
        return ms;
    }

    /**
     * Forks a subprocess and returns its wall-clock time in milliseconds.
     * stderr is merged into stdout; all output is discarded on success.
     * On failure, throws with the captured output as the message.
     */
    static long run(List<String> cmd, Path workDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        long t0 = System.nanoTime();
        Process p = pb.start();
        // readAllBytes() drains stdout and returns when the process closes the stream.
        byte[] out = p.getInputStream().readAllBytes();
        boolean done = p.waitFor(TIMEOUT, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("Process timed out after " + TIMEOUT + "s: " + cmd);
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException(
                "Process exited with " + p.exitValue() + ": " + cmd
                + "\n--- output ---\n" + new String(out));
        }
        return elapsedMs;
    }

    static void printHeader() {
        System.out.printf(
            "%-28s  %16s  %16s  %16s  %8s  %8s%n",
            "Workload", "Baseline (ms)", "Capture (ms)", "Replay (ms)", "Cap/Base", "Rep/Base");
        // System.out.println("-".repeat(105));
    }

    static double mean(long[] vals) {
        long sum = 0;
        for (long v : vals) sum += v;
        return (double) sum / vals.length;
    }

    static double std(long[] vals) {
        double m = mean(vals), s = 0;
        for (long v : vals) s += (v - m) * (v - m);
        return Math.sqrt(s / vals.length);
    }

    static void checkExists(Path p, String label) throws FileNotFoundException {
        if (!Files.exists(p))
            throw new FileNotFoundException(label + " not found: " + p);
    }

    static void deleteDir(Path dir) throws IOException {
        Files.walk(dir)
             .sorted(Comparator.reverseOrder())
             .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
    }
}
