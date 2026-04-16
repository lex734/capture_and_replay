package fidelity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Measures how faithfully the replay agent reproduces a captured run.
 *
 * <p>Runs the workload under capture once (recording stdout), then under replay
 * N times (default 100). Each replay run's stdout is compared against the
 * captured stdout to determine whether the final output matched. Injection-rate
 * and write-event statistics are collected as supplementary metrics via
 * {@code -Dtool.fidelity.output}.
 *
 * <p>Usage:
 * <pre>
 *   java -cp fidelity-benchmark.jar fidelity.FidelityBenchmark \
 *        &lt;capture-agent.jar&gt; &lt;replay-agent.jar&gt; [runs] [workload-class]
 * </pre>
 *
 * <p>Workload classes available in this jar:
 * <ul>
 *   <li>{@code fidelity.workload.WorkloadAtomicCounter} (default) — ATOMIC_RMW, near-deterministic</li>
 *   <li>{@code fidelity.workload.WorkloadPlainCounter}  — FIELD_WRITE race, less deterministic</li>
 *   <li>{@code fidelity.workload.WorkloadVolatileWrite} — volatile FIELD_WRITE</li>
 *   <li>{@code fidelity.workload.WorkloadSharedObject}  — volatile object-reference race</li>
 *   <li>{@code fidelity.workload.WorkloadArrayElement}  — ARRAY_WRITE, per-element tracking</li>
 * </ul>
 */
public class FidelityBenchmark {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: FidelityBenchmark <capture-agent.jar> <replay-agent.jar> [runs=100] [workload-class]");
            System.exit(1);
        }

        String captureJar = args[0];
        String replayJar  = args[1];
        int    runs       = args.length > 2 ? Integer.parseInt(args[2]) : 100;
        String workload   = args.length > 3 ? args[3] : "fidelity.workload.WorkloadAtomicCounter";

        String selfJar = selfJarPath();

        System.out.printf("=== Fidelity Benchmark: %s  (%d runs) ===%n%n", workload, runs);

        // Step 1: Capture once — record the "Final: X" line as the reference output
        System.out.println("Capturing...");
        List<String> captureCmd = List.of("java", "-javaagent:" + captureJar, "-cp", selfJar, workload);
        String capturedOutput = captureFinalLine(captureCmd);
        System.out.println("Capture output  : " + capturedOutput);
        System.out.println();

        // Step 2: Replay N times
        int  matchCount         = 0;
        int  structuralDivCount = 0;
        long sumWriteEvents     = 0;
        long sumAgreements      = 0;
        long sumInjections      = 0;

        System.out.println("Replaying...");
        for (int i = 0; i < runs; i++) {
            Path resultFile = Path.of("fidelity_result_" + i + ".properties");

            List<String> replayCmd = new ArrayList<>();
            replayCmd.add("java");
            replayCmd.add("-Dtool.fidelity.output=" + resultFile.toAbsolutePath());
            replayCmd.add("-javaagent:" + replayJar);
            replayCmd.add("-cp");
            replayCmd.add(selfJar);
            replayCmd.add(workload);

            String replayOutput = captureFinalLine(replayCmd);

            // Primary metric: did the replay print the same "Final: X" as capture?
            if (replayOutput.equals(capturedOutput)) matchCount++;

            // Supplementary metrics from the fidelity properties file
            if (Files.exists(resultFile)) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(resultFile)) {
                    p.load(r);
                } catch (IOException e) {
                    System.err.println("[WARN] Could not read result file for run " + i + ": " + e.getMessage());
                } finally {
                    Files.deleteIfExists(resultFile);
                }
                if (Boolean.parseBoolean(p.getProperty("structural_divergence", "false"))) structuralDivCount++;
                sumWriteEvents += Long.parseLong(p.getProperty("valued_events",       "0"));
                sumAgreements  += Long.parseLong(p.getProperty("natural_agreements", "0"));
                sumInjections  += Long.parseLong(p.getProperty("injections",         "0"));
            }

            if ((i + 1) % 10 == 0)
                System.out.printf("  %d / %d complete%n", i + 1, runs);
        }

        // Step 3: Aggregated report
        double naturalRate   = sumWriteEvents > 0 ? 100.0 * sumAgreements / sumWriteEvents : 0.0;
        double injectionRate = sumWriteEvents > 0 ? 100.0 * sumInjections  / sumWriteEvents : 0.0;

        System.out.println();
        System.out.println("===== FIDELITY BENCHMARK RESULTS =====");
        System.out.printf("Workload                : %s%n", workload);
        System.out.printf("Runs                    : %d%n", runs);
        System.out.printf("Final output match      : %d / %d  (%.1f%%)%n",
                matchCount, runs, 100.0 * matchCount / runs);
        System.out.printf("Structural divergences  : %d / %d  (%.1f%%)%n",
                structuralDivCount, runs, 100.0 * structuralDivCount / runs);
        System.out.printf("Avg write events / run  : %.0f%n", (double) sumWriteEvents / runs);
        System.out.printf("Natural agreement rate  : %.1f%%%n", naturalRate);
        System.out.printf("Injection rate          : %.1f%%%n", injectionRate);
        System.out.println("=======================================");
    }

    /**
     * Runs the given command and returns the last line starting with "Final:"
     * from stdout. All other stdout/stderr output is discarded. Returns an
     * empty string if no "Final:" line was produced.
     */
    private static String captureFinalLine(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start();

        // Capture stdout, keep only "Final:" lines
        StringBuilder finalLine = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("Final:")) finalLine.replace(0, finalLine.length(), line);
                }
            } catch (IOException ignored) {}
        });
        reader.start();
        // Discard stderr
        Thread errDrain = new Thread(() -> {
            try { p.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (IOException ignored) {}
        });
        errDrain.start();

        int exit = p.waitFor();
        reader.join();
        errDrain.join();

        if (exit != 0) System.err.println("[WARN] Process exited with code " + exit + ": " + cmd.get(cmd.size() - 1));
        return finalLine.toString();
    }

    private static String selfJarPath() throws URISyntaxException {
        return Path.of(FidelityBenchmark.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
    }
}
