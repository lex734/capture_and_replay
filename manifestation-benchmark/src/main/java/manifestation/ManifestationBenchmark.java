package manifestation;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Measures P(bug manifests in replay | captured trace is buggy) across the SCTBench suite.
 *
 * <p>For each class: runs capture in a loop until TARGET_BUGGY_CAPTURES buggy traces are
 * collected (or MAX_CAPTURE_ATTEMPTS total attempts are exhausted). Each buggy trace is
 * replayed REPLAYS_PER_CAPTURE times. Two primary metrics are reported:
 * <ul>
 *   <li><b>Overall manifestation rate</b>: bug_replay_runs / total_replay_runs
 *   <li><b>Manifestation rate | complete</b>: bug_replay_runs_where_complete / complete_replay_runs,
 *       where "complete" means the replay agent consumed every event in the trace.
 * </ul>
 *
 * <p>Classes for which no buggy trace is captured are skipped.
 *
 * <p>Usage:
 * <pre>
 *   java -cp manifestation-benchmark.jar manifestation.ManifestationBenchmark \
 *        &lt;capture-agent.jar&gt; &lt;replay-agent.jar&gt; &lt;sctbench.jar&gt; [class-or-classlist.txt]
 * </pre>
 *
 * <p>When no class list is given, reads benchmark/fray_benchmark/assets/sctbench.txt
 * relative to the current directory (run from the repo root).
 */
public class ManifestationBenchmark {

    private static final String REDUCED_TRACE_PATH_PROPERTY = "tool.reduced.trace";
    private static final String REDUCED_TRACE_FILE_NAME     = "trace-reduced.tsv";

    private static final long CAPTURE_TIMEOUT_MS  = 3_000;
    private static final long REPLAY_TIMEOUT_MS   = 5_000;
    private static final int  MAX_CAPTURE_ATTEMPTS  = 100;
    private static final int  TARGET_BUGGY_CAPTURES =  10;
    private static final int  PLAIN_RUNS            = 100;
    private static final int  TOTAL_REPLAY_RUNS     = 100;

    // Classes where a process timeout is the expected bug signal (deadlock).
    // Classes where silent hang (no output) is the bug signal — timeout counts as a bug.
    // Carter01Bad is excluded: it spins with tryLock and always self-detects via
    // "Deadlock detected" before exiting 0, so it never actually hangs.
    private static final Set<String> DEADLOCK_BENCHMARKS = new HashSet<>(Arrays.asList(
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Deadlock01Bad",
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Phase01Bad",
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Sync01Bad",
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Sync02Bad"
    ));

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: ManifestationBenchmark <capture-agent.jar> <replay-agent.jar>"
                + " <sctbench.jar> [class-or-classlist.txt]");
            System.exit(1);
        }

        String captureJar  = args[0];
        String replayJar   = args[1];
        String sctbenchJar = args[2];

        List<String> classes;
        if (args.length > 3) {
            String arg = args[3];
            Path p = Paths.get(arg);
            if (Files.isRegularFile(p)) {
                classes = Files.readAllLines(p).stream()
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            } else {
                classes = Collections.singletonList(arg);
            }
        } else {
            classes = loadDefaultClassList();
        }

        String captureJarAbs  = Paths.get(captureJar).toAbsolutePath().toString();
        String replayJarAbs   = Paths.get(replayJar).toAbsolutePath().toString();
        String sctbenchJarAbs = Paths.get(sctbenchJar).toAbsolutePath().toString();

        Path runsRoot = Paths.get("").toAbsolutePath()
            .resolve("manifestation-benchmark").resolve("work");
        Files.createDirectories(runsRoot);

        System.out.printf(
            "=== Manifestation Benchmark: SCTBench"
            + " (up to %d capture attempts, target %d buggy traces, %d replay runs) ===%n%n",
            MAX_CAPTURE_ATTEMPTS, TARGET_BUGGY_CAPTURES, TOTAL_REPLAY_RUNS);

        int  classesWithBugs         = 0;
        long grandBuggyCaptures      = 0;
        long grandPlainManifested    = 0;
        long grandReplayRuns         = 0;
        long grandManifested         = 0;
        long grandCompleteRuns       = 0;
        long grandManifestedComplete = 0;

        for (String cls : classes) {
            System.out.printf("--- %s ---%n", cls);

            Path classWorkRoot = runsRoot.resolve(sanitizeForPath(cls));
            deleteRecursivelyIfExists(classWorkRoot);
            Files.createDirectories(classWorkRoot);

            // Phase 1: collect buggy traces.
            int captureAttempts = 0;
            List<Path> buggyWorkDirs = new ArrayList<>();

            while (captureAttempts < MAX_CAPTURE_ATTEMPTS
                    && buggyWorkDirs.size() < TARGET_BUGGY_CAPTURES) {

                Path workDir = classWorkRoot.resolve("attempt_" + captureAttempts);
                Files.createDirectories(workDir);

                CaptureResult cr = runCapture(captureJarAbs, sctbenchJarAbs, cls, workDir);
                captureAttempts++;

                if (!cr.traceProduced || !cr.hadBug) {
                    deleteRecursivelyIfExists(workDir);
                    continue;
                }

                buggyWorkDirs.add(workDir);
                System.out.printf("  Buggy capture %2d (attempt %3d): %s%n",
                    buggyWorkDirs.size(), captureAttempts, cr.summary);
            }

            System.out.printf("  Capture attempts     : %d%n", captureAttempts);
            System.out.printf("  Buggy captures       : %d / %d attempts%n",
                buggyWorkDirs.size(), captureAttempts);

            if (buggyWorkDirs.isEmpty()) {
                System.out.printf("  (no buggy traces captured — skipped)%n%n");
                continue;
            }

            // Phase 2: plain runs — baseline with no instrumentation.
            Path plainDir = classWorkRoot.resolve("plain");
            Files.createDirectories(plainDir);
            int plainManifested = 0;
            for (int r = 0; r < PLAIN_RUNS; r++) {
                if (runPlain(sctbenchJarAbs, cls, plainDir)) plainManifested++;
            }

            // Phase 3: replay TOTAL_REPLAY_RUNS times, cycling round-robin across buggy traces.
            int replayRuns         = 0;
            int manifested         = 0;
            int completeRuns       = 0;
            int manifestedComplete = 0;

            for (int r = 0; r < TOTAL_REPLAY_RUNS; r++) {
                Path workDir = buggyWorkDirs.get(r % buggyWorkDirs.size());
                Path reducedTrace = workDir.resolve(REDUCED_TRACE_FILE_NAME).toAbsolutePath();
                Path propsFile = workDir.resolve("replay_" + r + ".properties");
                ReplayResult rr = runReplay(
                    replayJarAbs, sctbenchJarAbs, cls, workDir, reducedTrace, propsFile);
                replayRuns++;
                if (rr.hadBug) manifested++;
                if (rr.isComplete) {
                    completeRuns++;
                    if (rr.hadBug) manifestedComplete++;
                }
            }

            classesWithBugs++;
            grandBuggyCaptures      += buggyWorkDirs.size();
            grandPlainManifested    += plainManifested;
            grandReplayRuns         += replayRuns;
            grandManifested         += manifested;
            grandCompleteRuns       += completeRuns;
            grandManifestedComplete += manifestedComplete;

            double pPlain   = (double) plainManifested / PLAIN_RUNS;
            double pOverall = (double) manifested / replayRuns;
            System.out.printf("  Natural bug rate     : %d / %d  (%.1f%%)%n",
                plainManifested, PLAIN_RUNS, 100.0 * pPlain);
            System.out.printf("  Replay runs          : %d%n", replayRuns);
            System.out.printf("  Complete runs        : %d / %d%n", completeRuns, replayRuns);
            System.out.printf("  Bug manifested       : %d / %d  (%.1f%%)%n",
                manifested, replayRuns, 100.0 * pOverall);
            if (completeRuns > 0) {
                double pComplete = (double) manifestedComplete / completeRuns;
                System.out.printf("  Bug manifested|cmpl  : %d / %d  (%.1f%%)%n",
                    manifestedComplete, completeRuns, 100.0 * pComplete);
            } else {
                System.out.printf("  Bug manifested|cmpl  : N/A (no complete runs)%n");
            }
            System.out.println();
        }

        System.out.println("===== SUMMARY =====");
        System.out.printf("Classes tested           : %d%n", classes.size());
        System.out.printf("Classes with bug captures: %d / %d%n",
            classesWithBugs, classes.size());
        System.out.printf("Total buggy captures     : %d%n", grandBuggyCaptures);
        long grandPlainRuns = (long) classesWithBugs * PLAIN_RUNS;
        System.out.printf("Natural bug rate         : %d / %d  (%.1f%%)%n",
            grandPlainManifested, grandPlainRuns,
            grandPlainRuns > 0 ? 100.0 * grandPlainManifested / grandPlainRuns : 0.0);
        System.out.printf("Total replay runs        : %d%n", grandReplayRuns);
        System.out.printf("Total complete runs      : %d / %d%n",
            grandCompleteRuns, grandReplayRuns);
        if (grandReplayRuns > 0) {
            double pOverall = (double) grandManifested / grandReplayRuns;
            System.out.printf("Bug manifested           : %d / %d  (%.1f%%)%n",
                grandManifested, grandReplayRuns, 100.0 * pOverall);
        }
        if (grandCompleteRuns > 0) {
            double pComplete = (double) grandManifestedComplete / grandCompleteRuns;
            System.out.printf("Bug manifested | cmpl    : %d / %d  (%.1f%%)%n",
                grandManifestedComplete, grandCompleteRuns, 100.0 * pComplete);
        }
        System.out.println("===================");
    }

    // ---------- plain ----------

    private static boolean runPlain(String sctbenchJar, String cls, Path workDir)
            throws IOException, InterruptedException {
        RunResult r = runJava(
            Arrays.asList("-ea", "-cp", sctbenchJar, cls),
            workDir, CAPTURE_TIMEOUT_MS);
        return hasBugSignal(r.stdout, r.stderr)
            || (r.timedOut && DEADLOCK_BENCHMARKS.contains(cls));
    }

    // ---------- capture ----------

    private static CaptureResult runCapture(
            String captureJar, String sctbenchJar, String cls, Path workDir)
            throws IOException, InterruptedException {

        RunResult r = runJava(
            Arrays.asList("-javaagent:" + captureJar, "-ea", "-cp", sctbenchJar, cls),
            workDir, CAPTURE_TIMEOUT_MS);

        if (!Files.exists(workDir.resolve("trace.bin"))
                || !Files.exists(workDir.resolve(REDUCED_TRACE_FILE_NAME))) {
            String status = r.timedOut ? "timed out" : "exit " + r.exitCode;
            return new CaptureResult(false, "no trace produced (" + status + ")", false);
        }

        // Deadlock benchmarks: process timeout during capture IS the bug signal.
        // Non-deadlock benchmarks: timeout with no trace content isn't a reliable signal,
        // but we have a trace here so fall through to text-based detection.
        boolean hadBug = hasBugSignal(r.stdout, r.stderr)
            || (r.timedOut && DEADLOCK_BENCHMARKS.contains(cls));
        String status = r.timedOut ? "timed out" : "exit " + r.exitCode;
        return new CaptureResult(true, (hadBug ? "bug" : "clean") + " (" + status + ")", hadBug);
    }

    // ---------- replay ----------

    private static ReplayResult runReplay(
            String replayJar, String sctbenchJar, String cls,
            Path workDir, Path reducedTrace, Path propsFile)
            throws IOException, InterruptedException {

        List<String> extraArgs = Arrays.asList(
            "-D" + REDUCED_TRACE_PATH_PROPERTY + "=" + reducedTrace,
            "-Dtool.fidelity.output=" + propsFile.toAbsolutePath(),
            "-javaagent:" + replayJar,
            "-ea", "-cp", sctbenchJar, cls);

        RunResult r = runJava(extraArgs, workDir, REPLAY_TIMEOUT_MS);

        boolean hadBug = hasBugSignal(r.stdout, r.stderr)
            || (r.timedOut && DEADLOCK_BENCHMARKS.contains(cls));

        boolean isComplete = false;
        if (Files.exists(propsFile)) {
            Properties p = new Properties();
            try (Reader rd = Files.newBufferedReader(propsFile)) { p.load(rd); }
            catch (IOException ignored) {}
            Files.deleteIfExists(propsFile);
            long eventsMatched = Long.parseLong(p.getProperty("events_matched", "0"));
            long eventsTotal   = Long.parseLong(p.getProperty("events_total",   "0"));
            isComplete = eventsTotal > 0 && eventsMatched == eventsTotal;
        }

        return new ReplayResult(hadBug, isComplete);
    }

    // ---------- signal detection ----------

    private static boolean hasBugSignal(String stdout, String stderr) {
        String combined = stdout + stderr;
        return combined.contains("AssertionError")
            || combined.contains("Bug Found!")
            || combined.contains("Bug found!")
            || combined.contains("Deadlock detected")
            || combined.contains("RuntimeException: deadlock");
    }

    // ---------- process execution ----------

    private static RunResult runJava(List<String> extraArgs, Path workDir, long timeoutMs)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(Arrays.asList(
            javaExecutable(),
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.concurrent.locks=ALL-UNNAMED"
        ));
        cmd.addAll(extraArgs);

        Process p = new ProcessBuilder(cmd)
            .directory(workDir.toFile())
            .redirectErrorStream(false)
            .start();

        StringWriter outSW = new StringWriter(), errSW = new StringWriter();
        Thread outT = drain(p.getInputStream(), outSW);
        Thread errT = drain(p.getErrorStream(), errSW);

        boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroy();
            finished = p.waitFor(2, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            p.waitFor();
        }
        outT.join();
        errT.join();

        return new RunResult(outSW.toString(), errSW.toString(),
            finished ? p.exitValue() : 124, !finished);
    }

    private static Thread drain(InputStream is, Writer out) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) out.write(line + "\n");
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
            Path candidate = Paths.get(javaHome, "bin", "java");
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return "java";
    }

    private static String sanitizeForPath(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteRecursivelyIfExists(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ---------- class list ----------

    private static List<String> loadDefaultClassList() throws IOException {
        Path defaultList = Paths.get("benchmark", "fray_benchmark", "assets", "sctbench.txt");
        if (Files.isRegularFile(defaultList)) {
            return Files.readAllLines(defaultList).stream()
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        }
        throw new FileNotFoundException(
            "Default class list not found at: " + defaultList.toAbsolutePath()
            + "\nRun from the repo root, or pass a class list as the fourth argument.");
    }

    // ---------- value types ----------

    private static final class CaptureResult {
        final boolean traceProduced;
        final String  summary;
        final boolean hadBug;
        CaptureResult(boolean traceProduced, String summary, boolean hadBug) {
            this.traceProduced = traceProduced;
            this.summary       = summary;
            this.hadBug        = hadBug;
        }
    }

    private static final class ReplayResult {
        final boolean hadBug;
        final boolean isComplete;
        ReplayResult(boolean hadBug, boolean isComplete) {
            this.hadBug    = hadBug;
            this.isComplete = isComplete;
        }
    }

    private static final class RunResult {
        final String  stdout;
        final String  stderr;
        final int     exitCode;
        final boolean timedOut;
        RunResult(String stdout, String stderr, int exitCode, boolean timedOut) {
            this.stdout   = stdout;
            this.stderr   = stderr;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
        }
    }
}
