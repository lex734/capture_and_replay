package fidelity;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Measures how faithfully the replay agent reproduces captured SCTBench runs.
 *
 * <p>For each class in the SCTBench suite: captures one run unconditionally,
 * then replays that trace N times (default 30) and reports per-class metrics.
 *
 * <p>Usage:
 * <pre>
 *   java -cp fidelity-benchmark.jar fidelity.FidelityBenchmark \
 *        &lt;capture-agent.jar&gt; &lt;replay-agent.jar&gt; &lt;sctbench.jar&gt; [runs=30] [class-or-classlist.txt]
 * </pre>
 */
public class FidelityBenchmark {

    private static final long CAPTURE_TIMEOUT_MS = 3_000;
    private static final long REPLAY_TIMEOUT_MS  = 5_000;

    // Benchmarks where timeout is the expected bug signal (deadlock).
    private static final Set<String> DEADLOCK_BENCHMARKS = new HashSet<>(Arrays.asList(
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Carter01Bad",
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Deadlock01Bad",
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Phase01Bad",
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Sync01Bad",
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Sync02Bad"
    ));

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: FidelityBenchmark <capture-agent.jar> <replay-agent.jar>"
                + " <sctbench.jar> [runs=30] [class-or-classlist.txt]");
            System.exit(1);
        }

        String captureJar  = args[0];
        String replayJar   = args[1];
        String sctbenchJar = args[2];
        int    runs        = args.length > 3 ? Integer.parseInt(args[3]) : 30;

        List<String> classes;
        if (args.length > 4) {
            String arg = args[4];
            Path p = Paths.get(arg);
            if (Files.isRegularFile(p)) {
                classes = Files.readAllLines(p).stream()
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            } else {
                classes = Collections.singletonList(arg);
            }
        } else {
            classes = loadBundledClassList();
        }

        System.out.printf("=== Fidelity Benchmark: SCTBench (%d replay runs per class) ===%n%n", runs);

        // All subprocesses share the harness working directory. Sequential execution
        // means there is no race on trace.bin between different classes.
        Path workDir  = Paths.get("").toAbsolutePath();
        Path traceFile = workDir.resolve("trace.bin");

        // Summary accumulators
        int  classesReplayed   = 0;
        long totalReplays      = 0;
        long totalCompleteRuns = 0;
        long totalIncompleteRuns = 0;
        long totalDivergences  = 0;
        long totalOutcomeMatch = 0;
        long totalOutcomeComplete = 0;
        long totalOutcomeIncomplete = 0;
        long totalOutcomeDiverged = 0;
        long totalCompleteMatched = 0;
        long totalCompleteRemaining = 0;
        long totalCompleteEvents = 0;
        long totalIncompleteMatched = 0;
        long totalIncompleteRemaining = 0;
        long totalIncompleteEvents = 0;
        long totalDivergedMatched = 0;
        long totalDivergedRemaining = 0;
        long totalDivergedEvents = 0;
        long totalValued       = 0;
        long totalAgreements   = 0;
        long totalInjections   = 0;
        long totalNoInjectDisagreements = 0;

        for (String cls : classes) {
            System.out.printf("--- %s ---%n", cls);

            Files.deleteIfExists(traceFile);

            CaptureResult cr = runCapture(captureJar, sctbenchJar, cls, workDir);
            System.out.printf("  Capture            : %s%n", cr.summary);
            if (!cr.traceProduced) {
                System.out.println();
                continue;
            }
            classesReplayed++;

            // Per-class accumulators
            long traceSize      = 0;  // events_total (constant per class; take max observed)
            int  runsWithData   = 0;
            int  completeRuns   = 0;
            int  incompleteRuns = 0;
            int  divergences    = 0;
            int  outcomeMatch   = 0;
            int  outcomeComplete = 0;
            int  outcomeIncomplete = 0;
            int  outcomeDiverged = 0;
            long completeMatched = 0, completeRemaining = 0, completeEvents = 0;
            long incompleteMatched = 0, incompleteRemaining = 0, incompleteEvents = 0;
            long divergedMatched = 0, divergedRemaining = 0, divergedEvents = 0;
            long sumValued      = 0, sumAgreements = 0, sumInjections = 0, sumNoInjectDisagreements = 0;
            Map<String, Integer> incompleteReasons = new LinkedHashMap<>();

            for (int i = 0; i < runs; i++) {
                Path propsFile = workDir.resolve("fidelity_run_" + i + ".properties");
                ReplayResult rr = runReplay(replayJar, sctbenchJar, cls, workDir, propsFile);

                if (rr.eventsTotal > traceSize) traceSize = rr.eventsTotal;
                if (rr.eventsTotal > 0) {
                    runsWithData++;
                }
                long remaining = Math.max(0, rr.eventsTotal - rr.eventsMatched);
                if (rr.diverged) {
                    divergences++;
                    divergedMatched += rr.eventsMatched;
                    divergedRemaining += remaining;
                    divergedEvents += rr.eventsTotal;
                } else if (rr.incomplete) {
                    incompleteRuns++;
                    incompleteMatched += rr.eventsMatched;
                    incompleteRemaining += remaining;
                    incompleteEvents += rr.eventsTotal;
                    String reason = rr.incompleteReason == null || rr.incompleteReason.isEmpty()
                            ? "unknown"
                            : rr.incompleteReason;
                    incompleteReasons.put(reason, incompleteReasons.getOrDefault(reason, 0) + 1);
                } else {
                    completeRuns++;
                    completeMatched += rr.eventsMatched;
                    completeRemaining += remaining;
                    completeEvents += rr.eventsTotal;
                }
                boolean outMatch = rr.outcomeMatchesCapture(cr.hadBug);
                if (outMatch) outcomeMatch++;
                if (rr.diverged && outMatch) outcomeDiverged++;
                if (rr.incomplete && outMatch) outcomeIncomplete++;
                if (!rr.diverged && !rr.incomplete && outMatch) outcomeComplete++;

                sumValued     += rr.valuedEvents;
                sumAgreements += rr.naturalAgreements;
                sumInjections += rr.injections;
                sumNoInjectDisagreements += rr.noInjectDisagreements;
            }

            // Per-class display
            System.out.printf("  Trace size         : %d events%n", traceSize);
            System.out.printf("  Complete runs      : %d / %d%n", completeRuns, runs);
            System.out.printf("  Incomplete runs    : %d / %d%n", incompleteRuns, runs);
            System.out.printf("  Structural divs    : %d / %d%n", divergences, runs);
            System.out.printf("  Outcome reproduced : %d / %d  (%.2f%%)%n",
                outcomeMatch, runs, 100.0 * outcomeMatch / runs);
            if (completeRuns > 0) {
                System.out.printf("  ...complete runs   : %d / %d%n", outcomeComplete, completeRuns);
            }
            if (incompleteRuns > 0) {
                System.out.printf("  ...incomplete runs : %d / %d%n", outcomeIncomplete, incompleteRuns);
            }
            if (divergences > 0) {
                System.out.printf("  ...diverged runs   : %d / %d%n", outcomeDiverged, divergences);
            }
            if (completeRuns > 0 && completeEvents > 0) {
                System.out.printf("  Matched (complete) : %.2f avg / run  (%.2f%%)%n",
                    (double) completeMatched / completeRuns,
                    100.0 * completeMatched / completeEvents);
            }
            if (incompleteRuns > 0 && incompleteEvents > 0) {
                System.out.printf("  Matched (incompl.) : %.2f avg / run  (%.2f%%)%n",
                    (double) incompleteMatched / incompleteRuns,
                    100.0 * incompleteMatched / incompleteEvents);
                System.out.printf("  Remaining (incompl.): %.2f avg / run  (%.2f%%)%n",
                    (double) incompleteRemaining / incompleteRuns,
                    100.0 * incompleteRemaining / incompleteEvents);
                if (!incompleteReasons.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, Integer> e : incompleteReasons.entrySet()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(e.getKey()).append('=').append(e.getValue());
                    }
                    System.out.printf("  Incomplete reasons : %s%n", sb);
                }
            }
            if (divergences > 0 && divergedEvents > 0) {
                System.out.printf("  Matched (pre-div)  : %.2f avg / run  (%.2f%%)%n",
                    (double) divergedMatched / divergences,
                    100.0 * divergedMatched / divergedEvents);
                System.out.printf("  Remaining (post-div): %.2f avg / run  (%.2f%%)%n",
                    (double) divergedRemaining / divergences,
                    100.0 * divergedRemaining / divergedEvents);
            }
            if (sumValued > 0) {
                System.out.printf("  Valued events      : %.2f avg / run%n",
                    (double) sumValued / runsWithData);
                System.out.printf("  Natural agreement  : %.2f / %.2f  (%.2f%%)%n",
                    (double) sumAgreements / runsWithData, (double) sumValued / runsWithData,
                    100.0 * sumAgreements / sumValued);
                System.out.printf("  Injections         : %.2f / %.2f  (%.2f%%)%n",
                    (double) sumInjections / runsWithData, (double) sumValued / runsWithData,
                    100.0 * sumInjections / sumValued);
                System.out.printf("  No-inject disagree : %.2f / %.2f  (%.2f%%)%n",
                    (double) sumNoInjectDisagreements / runsWithData, (double) sumValued / runsWithData,
                    100.0 * sumNoInjectDisagreements / sumValued);
            }
            System.out.println();

            // Accumulate into summary
            totalReplays       += runs;
            totalCompleteRuns  += completeRuns;
            totalIncompleteRuns += incompleteRuns;
            totalDivergences   += divergences;
            totalOutcomeMatch  += outcomeMatch;
            totalOutcomeComplete += outcomeComplete;
            totalOutcomeIncomplete += outcomeIncomplete;
            totalOutcomeDiverged += outcomeDiverged;
            totalCompleteMatched += completeMatched;
            totalCompleteRemaining += completeRemaining;
            totalCompleteEvents += completeEvents;
            totalIncompleteMatched += incompleteMatched;
            totalIncompleteRemaining += incompleteRemaining;
            totalIncompleteEvents += incompleteEvents;
            totalDivergedMatched += divergedMatched;
            totalDivergedRemaining += divergedRemaining;
            totalDivergedEvents += divergedEvents;
            totalValued        += sumValued;
            totalAgreements    += sumAgreements;
            totalInjections    += sumInjections;
            totalNoInjectDisagreements += sumNoInjectDisagreements;
        }

        // Summary
        System.out.println("===== SUMMARY =====");
        System.out.printf("Classes tested        : %d%n", classes.size());
        System.out.printf("Classes replayed      : %d / %d%n", classesReplayed, classes.size());
        if (totalReplays > 0) {
            System.out.printf("Complete runs         : %d / %d runs  (%.1f%%)%n",
                totalCompleteRuns, totalReplays, 100.0 * totalCompleteRuns / totalReplays);
            System.out.printf("Incomplete runs       : %d / %d runs  (%.1f%%)%n",
                totalIncompleteRuns, totalReplays, 100.0 * totalIncompleteRuns / totalReplays);
            System.out.printf("Structural divs       : %d / %d runs  (%.1f%%)%n",
                totalDivergences, totalReplays, 100.0 * totalDivergences / totalReplays);
            System.out.printf("Outcome reproduced    : %d / %d runs  (%.1f%%)%n",
                totalOutcomeMatch, totalReplays, 100.0 * totalOutcomeMatch / totalReplays);
            if (totalCompleteRuns > 0) {
                System.out.printf("...complete runs      : %d / %d runs  (%.1f%%)%n",
                    totalOutcomeComplete, totalCompleteRuns,
                    100.0 * totalOutcomeComplete / totalCompleteRuns);
            }
            if (totalIncompleteRuns > 0) {
                System.out.printf("...incomplete runs    : %d / %d runs  (%.1f%%)%n",
                    totalOutcomeIncomplete, totalIncompleteRuns,
                    100.0 * totalOutcomeIncomplete / totalIncompleteRuns);
            }
            if (totalDivergences > 0) {
                System.out.printf("...diverged runs      : %d / %d runs  (%.1f%%)%n",
                    totalOutcomeDiverged, totalDivergences,
                    100.0 * totalOutcomeDiverged / totalDivergences);
            }
            if (totalCompleteRuns > 0 && totalCompleteEvents > 0) {
                System.out.printf("Matched (complete)    : %.1f%%%n",
                    100.0 * totalCompleteMatched / totalCompleteEvents);
            }
            if (totalIncompleteRuns > 0 && totalIncompleteEvents > 0) {
                System.out.printf("Matched (incomplete)  : %.1f%%%n",
                    100.0 * totalIncompleteMatched / totalIncompleteEvents);
                System.out.printf("Remaining (incomplete): %.1f%%%n",
                    100.0 * totalIncompleteRemaining / totalIncompleteEvents);
            }
            if (totalDivergences > 0 && totalDivergedEvents > 0) {
                System.out.printf("Matched (pre-div)     : %.1f%%%n",
                    100.0 * totalDivergedMatched / totalDivergedEvents);
                System.out.printf("Remaining (post-div)  : %.1f%%%n",
                    100.0 * totalDivergedRemaining / totalDivergedEvents);
            }
        }
        if (totalValued > 0) {
            System.out.printf("Valued events         : %d%n", totalValued);
            System.out.printf("Natural agreement     : %d / %d  (%.1f%%)%n",
                totalAgreements, totalValued, 100.0 * totalAgreements / totalValued);
            System.out.printf("Injections            : %d / %d  (%.1f%%)%n",
                totalInjections, totalValued, 100.0 * totalInjections / totalValued);
            System.out.printf("No-inject disagreements: %d / %d  (%.1f%%)%n",
                totalNoInjectDisagreements, totalValued,
                100.0 * totalNoInjectDisagreements / totalValued);
        }
        System.out.println("===================");
    }

    // ---------- capture ----------

    private static CaptureResult runCapture(
            String captureJar, String sctbenchJar, String cls, Path workDir)
            throws IOException, InterruptedException {

        RunResult r = runJava(
            Arrays.asList("-javaagent:" + captureJar, "-ea", "-cp", sctbenchJar, cls),
            workDir, CAPTURE_TIMEOUT_MS);

        if (!Files.exists(workDir.resolve("trace.bin"))) {
            String status = r.timedOut ? "timed out" : "exit " + r.exitCode;
            return new CaptureResult(false, "no trace.bin produced (" + status + ")", false);
        }

        boolean hadBug = hasBugSignal(r.stdout, r.stderr)
            || (r.timedOut && DEADLOCK_BENCHMARKS.contains(cls));
        String outcome = hadBug ? "bug" : "clean";
        String status  = r.timedOut ? "timed out" : "exit " + r.exitCode;
        return new CaptureResult(true, outcome + " (" + status + ")", hadBug);
    }

    // ---------- replay ----------

    private static ReplayResult runReplay(
            String replayJar, String sctbenchJar, String cls,
            Path workDir, Path propsFile)
            throws IOException, InterruptedException {

        List<String> extraArgs = Arrays.asList(
            "-Dtool.fidelity.output=" + propsFile.toAbsolutePath(),
            "-javaagent:" + replayJar,
            "-ea", "-cp", sctbenchJar, cls);

        RunResult r = runJava(extraArgs, workDir, REPLAY_TIMEOUT_MS);

        boolean hadBug  = hasBugSignal(r.stdout, r.stderr)
            || (r.timedOut && DEADLOCK_BENCHMARKS.contains(cls));
        boolean diverged = false;
        boolean incomplete = false;
        String incompleteReason = "";
        String incompleteDetails = "";

        long eventsMatched = 0, eventsTotal = 0;
        long valuedEvents = 0, naturalAgreements = 0, injections = 0, noInjectDisagreements = 0;

        if (Files.exists(propsFile)) {
            Properties p = new Properties();
            try (Reader rd = Files.newBufferedReader(propsFile)) { p.load(rd); }
            catch (IOException ignored) {}
            Files.deleteIfExists(propsFile);
            diverged          = Boolean.parseBoolean(p.getProperty("structural_divergence", "false"));
            incomplete        = Boolean.parseBoolean(p.getProperty("incomplete", "false"));
            incompleteReason  = p.getProperty("incomplete_reason", "");
            incompleteDetails = p.getProperty("incomplete_details", "");
            eventsMatched     = Long.parseLong(p.getProperty("events_matched",      "0"));
            eventsTotal       = Long.parseLong(p.getProperty("events_total",        "0"));
            valuedEvents      = Long.parseLong(p.getProperty("valued_events",       "0"));
            naturalAgreements = Long.parseLong(p.getProperty("natural_agreements",  "0"));
            injections        = Long.parseLong(p.getProperty("injections",          "0"));
            noInjectDisagreements = Long.parseLong(p.getProperty("no_inject_disagreements", "0"));
        } else {
            // Fallback for runs that fail before the replay shutdown hook writes stats.
            diverged = hasDivergenceSignal(r.stdout, r.stderr);
        }

        if (diverged) incomplete = false;
        if (incomplete && r.timedOut) {
            incompleteReason = incompleteReason.isEmpty()
                ? "timeout"
                : "timeout+" + incompleteReason;
        } else if (incomplete && incompleteReason.isEmpty()) {
            incompleteReason = "unknown";
        }

        return new ReplayResult(hadBug, diverged, incomplete, eventsMatched, eventsTotal,
            valuedEvents, naturalAgreements, injections, noInjectDisagreements,
            incompleteReason, incompleteDetails);
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
            p.destroy(); // SIGTERM — lets shutdown hooks (fidelity report) run
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
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
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

    private static boolean hasDivergenceSignal(String stdout, String stderr) {
        String combined = stdout + stderr;
        return combined.contains("[DIVERGENCE]")
            || combined.contains("structurally diverged")
            || combined.contains("valued event mismatch")
            || combined.contains("site mismatch")
            || combined.contains("object mismatch");
    }

    // ---------- helpers ----------

    private static List<String> loadBundledClassList() throws IOException {
        try (InputStream is = FidelityBenchmark.class.getResourceAsStream("/sctbench.txt")) {
            if (is == null) throw new IOException("sctbench.txt not found in jar");
            return new BufferedReader(new InputStreamReader(is)).lines()
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        }
    }

    // ---------- result types ----------

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
        final boolean diverged;
        final boolean incomplete;
        final long    eventsMatched;
        final long    eventsTotal;
        final long    valuedEvents;
        final long    naturalAgreements;
        final long    injections;
        final long    noInjectDisagreements;
        final String  incompleteReason;
        final String  incompleteDetails;
        ReplayResult(boolean hadBug, boolean diverged, boolean incomplete,
                     long eventsMatched, long eventsTotal,
                     long valuedEvents, long naturalAgreements, long injections,
                     long noInjectDisagreements,
                     String incompleteReason, String incompleteDetails) {
            this.hadBug            = hadBug;
            this.diverged          = diverged;
            this.incomplete        = incomplete;
            this.eventsMatched     = eventsMatched;
            this.eventsTotal       = eventsTotal;
            this.valuedEvents      = valuedEvents;
            this.naturalAgreements = naturalAgreements;
            this.injections        = injections;
            this.noInjectDisagreements = noInjectDisagreements;
            this.incompleteReason  = incompleteReason;
            this.incompleteDetails = incompleteDetails;
        }
        boolean outcomeMatchesCapture(boolean captureHadBug) {
            return hadBug == captureHadBug;
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
