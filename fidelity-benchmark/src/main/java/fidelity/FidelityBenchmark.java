package fidelity;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Measures how faithfully the replay agent reproduces captured SCTBench runs.
 *
 * <p>For each class in the SCTBench suite: performs 10 capture trials by default.
 * Each successful capture is replayed 10 times, then replay metrics are aggregated
 * separately for traces whose capture outcome was buggy vs clean.
 *
 * <p>Usage:
 * <pre>
 *   java -cp fidelity-benchmark.jar fidelity.FidelityBenchmark \
 *        &lt;capture-agent.jar&gt; &lt;replay-agent.jar&gt; &lt;sctbench.jar&gt; [class-or-classlist.txt]
 * </pre>
 */
public class FidelityBenchmark {

    private static final long CAPTURE_TIMEOUT_MS = 3_000;
    private static final long REPLAY_TIMEOUT_MS  = 5_000;
    private static final int DEFAULT_CAPTURE_TRIALS = 10;
    private static final int DEFAULT_REPLAYS_PER_CAPTURE = 10;

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
                + " <sctbench.jar> [class-or-classlist.txt]");
            System.exit(1);
        }

        String captureJar  = args[0];
        String replayJar   = args[1];
        String sctbenchJar = args[2];
        int captureTrials = DEFAULT_CAPTURE_TRIALS;
        int replayRunsPerCapture = DEFAULT_REPLAYS_PER_CAPTURE;

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
            classes = loadBundledClassList();
        }

        System.out.printf(
            "=== Fidelity Benchmark: SCTBench (%d capture trials x %d replays per capture) ===%n%n",
            captureTrials, replayRunsPerCapture);

        // All subprocesses share the harness working directory. Sequential execution
        // means there is no race on trace.bin between different classes.
        Path workDir  = Paths.get("").toAbsolutePath();
        Path traceFile = workDir.resolve("trace.bin");

        // Summary accumulators
        int classesReplayed = 0;
        OutcomeBucketSummary bugSummary = new OutcomeBucketSummary("bug");
        OutcomeBucketSummary cleanSummary = new OutcomeBucketSummary("clean");
        for (String cls : classes) {
            System.out.printf("--- %s ---%n", cls);

            ClassSummary classSummary = new ClassSummary(captureTrials);

            for (int captureTrial = 0; captureTrial < captureTrials; captureTrial++) {
                Files.deleteIfExists(traceFile);

                CaptureResult cr = runCapture(captureJar, sctbenchJar, cls, workDir);
                classSummary.captureAttempts++;
                System.out.printf("  Capture %2d/%d       : %s%n",
                    captureTrial + 1, captureTrials, cr.summary);

                if (!cr.traceProduced) {
                    continue;
                }

                classSummary.successfulCaptures++;
                OutcomeBucketStats bucket = classSummary.bucketFor(cr.hadBug);
                bucket.captureCount++;

                for (int replayRun = 0; replayRun < replayRunsPerCapture; replayRun++) {
                    Path propsFile = workDir.resolve(
                        "fidelity_capture_" + captureTrial + "_replay_" + replayRun + ".properties");
                    ReplayResult rr = runReplay(replayJar, sctbenchJar, cls, workDir, propsFile);
                    bucket.recordReplay(rr, cr.hadBug);
                }
            }

            if (classSummary.successfulCaptures > 0) {
                classesReplayed++;
            }

            // Per-class display
            System.out.printf("  Successful captures: %d / %d%n",
                classSummary.successfulCaptures, classSummary.captureAttempts);
            classSummary.printBucket("bug");
            classSummary.printBucket("clean");
            System.out.println();

            bugSummary.add(classSummary.buggy);
            cleanSummary.add(classSummary.clean);
        }

        // Summary
        System.out.println("===== SUMMARY =====");
        System.out.printf("Classes tested        : %d%n", classes.size());
        System.out.printf("Classes replayed      : %d / %d%n", classesReplayed, classes.size());
        bugSummary.print();
        cleanSummary.print();
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
        long eventsMatched = 0, eventsTotal = 0;
        long valuedEvents = 0, naturalAgreements = 0, injections = 0, noInjectDisagreements = 0;

        if (Files.exists(propsFile)) {
            Properties p = new Properties();
            try (Reader rd = Files.newBufferedReader(propsFile)) { p.load(rd); }
            catch (IOException ignored) {}
            Files.deleteIfExists(propsFile);
            diverged          = Boolean.parseBoolean(p.getProperty("structural_divergence", "false"));
            eventsMatched     = Long.parseLong(p.getProperty("events_matched",      "0"));
            eventsTotal       = Long.parseLong(p.getProperty("events_total",        "0"));
            valuedEvents      = Long.parseLong(p.getProperty("valued_events",       "0"));
            naturalAgreements = Long.parseLong(p.getProperty("natural_agreements",  "0"));
            injections        = Long.parseLong(p.getProperty("injections",          "0"));
            noInjectDisagreements = Long.parseLong(p.getProperty("no_inject_disagreements", "0"));
        } else {
            diverged = hasDivergenceSignal(r.stdout, r.stderr);
        }

        return new ReplayResult(hadBug, diverged, eventsMatched, eventsTotal,
            valuedEvents, naturalAgreements, injections, noInjectDisagreements);
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
        final long    eventsMatched;
        final long    eventsTotal;
        final long    valuedEvents;
        final long    naturalAgreements;
        final long    injections;
        final long    noInjectDisagreements;
        ReplayResult(boolean hadBug, boolean diverged, long eventsMatched, long eventsTotal,
                     long valuedEvents, long naturalAgreements, long injections,
                     long noInjectDisagreements) {
            this.hadBug            = hadBug;
            this.diverged          = diverged;
            this.eventsMatched     = eventsMatched;
            this.eventsTotal       = eventsTotal;
            this.valuedEvents      = valuedEvents;
            this.naturalAgreements = naturalAgreements;
            this.injections        = injections;
            this.noInjectDisagreements = noInjectDisagreements;
        }
        boolean outcomeMatchesCapture(boolean captureHadBug) {
            return hadBug == captureHadBug;
        }
    }

    private static final class OutcomeBucketStats {
        long captureCount;
        long replayRuns;
        long traceSize;
        long completeRuns;
        long divergenceRuns;
        long outcomeMatch;
        long outcomeComplete;
        long completeMatched;
        long completeEvents;
        long valuedEvents;
        long naturalAgreements;
        long injections;
        long noInjectDisagreements;

        void recordReplay(ReplayResult rr, boolean captureHadBug) {
            replayRuns++;
            if (rr.eventsTotal > traceSize) traceSize = rr.eventsTotal;
            if (rr.diverged) divergenceRuns++;
            valuedEvents += rr.valuedEvents;
            naturalAgreements += rr.naturalAgreements;
            injections += rr.injections;
            noInjectDisagreements += rr.noInjectDisagreements;

            boolean isComplete = (rr.eventsMatched == rr.eventsTotal);
            if (isComplete) {
                completeRuns++;
                completeMatched += rr.eventsMatched;
                completeEvents += rr.eventsTotal;
            }

            boolean outMatch = rr.outcomeMatchesCapture(captureHadBug);
            if (outMatch) outcomeMatch++;
            if (isComplete && outMatch) outcomeComplete++;
        }
    }

    private static final class ClassSummary {
        final int expectedCaptureTrials;
        final OutcomeBucketStats buggy = new OutcomeBucketStats();
        final OutcomeBucketStats clean = new OutcomeBucketStats();
        long captureAttempts;
        long successfulCaptures;

        ClassSummary(int expectedCaptureTrials) {
            this.expectedCaptureTrials = expectedCaptureTrials;
        }

        OutcomeBucketStats bucketFor(boolean hadBug) {
            return hadBug ? buggy : clean;
        }

        void printBucket(String label) {
            OutcomeBucketStats bucket = "bug".equals(label) ? buggy : clean;
            if (bucket.captureCount == 0) {
                System.out.printf("  %s captures       : 0 / %d%n",
                    padLabel(label), expectedCaptureTrials);
                return;
            }

            System.out.printf("  %s captures       : %d / %d%n",
                padLabel(label), bucket.captureCount, expectedCaptureTrials);
            System.out.printf("    Trace size         : %d events%n", bucket.traceSize);
            System.out.printf("    Replay runs        : %d%n", bucket.replayRuns);
            System.out.printf("    Complete runs      : %d / %d%n",
                bucket.completeRuns, bucket.replayRuns);
            System.out.printf("    Structural divs    : %d / %d%n",
                bucket.divergenceRuns, bucket.replayRuns);
            System.out.printf("    Outcome reproduced : %d / %d  (%.2f%%)%n",
                bucket.outcomeMatch, bucket.replayRuns, 100.0 * bucket.outcomeMatch / bucket.replayRuns);
            if (bucket.completeRuns > 0) {
                System.out.printf("    ...complete runs   : %d / %d%n",
                    bucket.outcomeComplete, bucket.completeRuns);
            }
            if (bucket.completeEvents > 0) {
                System.out.printf("    Matched (complete) : %.2f avg / run  (%.2f%%)%n",
                    (double) bucket.completeMatched / bucket.completeRuns,
                    100.0 * bucket.completeMatched / bucket.completeEvents);
            }
            if (bucket.valuedEvents > 0) {
                System.out.printf("    Valued events      : %.2f avg / run%n",
                    (double) bucket.valuedEvents / bucket.replayRuns);
                System.out.printf("    Natural agreement  : %d / %d  (%.2f%%)%n",
                    bucket.naturalAgreements, bucket.valuedEvents,
                    100.0 * bucket.naturalAgreements / bucket.valuedEvents);
                System.out.printf("    Injections         : %d / %d  (%.2f%%)%n",
                    bucket.injections, bucket.valuedEvents,
                    100.0 * bucket.injections / bucket.valuedEvents);
                System.out.printf("    No-inject disagree : %d / %d  (%.2f%%)%n",
                    bucket.noInjectDisagreements, bucket.valuedEvents,
                    100.0 * bucket.noInjectDisagreements / bucket.valuedEvents);
            }
        }

        private static String padLabel(String label) {
            return String.format("%-5s", label);
        }
    }

    private static final class OutcomeBucketSummary {
        final String label;
        long captures;
        long replayRuns;
        long completeRuns;
        long divergenceRuns;
        long outcomeMatch;
        long outcomeComplete;
        long completeMatched;
        long completeEvents;
        long valuedEvents;
        long naturalAgreements;
        long injections;
        long noInjectDisagreements;

        OutcomeBucketSummary(String label) {
            this.label = label;
        }

        void add(OutcomeBucketStats stats) {
            captures += stats.captureCount;
            replayRuns += stats.replayRuns;
            completeRuns += stats.completeRuns;
            divergenceRuns += stats.divergenceRuns;
            outcomeMatch += stats.outcomeMatch;
            outcomeComplete += stats.outcomeComplete;
            completeMatched += stats.completeMatched;
            completeEvents += stats.completeEvents;
            valuedEvents += stats.valuedEvents;
            naturalAgreements += stats.naturalAgreements;
            injections += stats.injections;
            noInjectDisagreements += stats.noInjectDisagreements;
        }

        void print() {
            System.out.printf("%s captures         : %d%n", capitalize(label), captures);
            if (captures == 0) {
                return;
            }

            System.out.printf("%s replay runs      : %d%n", capitalize(label), replayRuns);
            System.out.printf("%s complete runs    : %d / %d runs  (%.1f%%)%n",
                capitalize(label), completeRuns, replayRuns, 100.0 * completeRuns / replayRuns);
            System.out.printf("%s structural divs  : %d / %d runs  (%.1f%%)%n",
                capitalize(label), divergenceRuns, replayRuns, 100.0 * divergenceRuns / replayRuns);
            System.out.printf("%s outcome repr.    : %d / %d runs  (%.1f%%)%n",
                capitalize(label), outcomeMatch, replayRuns, 100.0 * outcomeMatch / replayRuns);
            if (completeRuns > 0) {
                System.out.printf("%s ...complete runs : %d / %d runs  (%.1f%%)%n",
                    capitalize(label), outcomeComplete, completeRuns,
                    100.0 * outcomeComplete / completeRuns);
            }
            if (completeEvents > 0) {
                System.out.printf("%s matched complete : %.1f%%%n",
                    capitalize(label), 100.0 * completeMatched / completeEvents);
            }
            if (valuedEvents > 0) {
                System.out.printf("%s natural agr.    : %d / %d  (%.1f%%)%n",
                    capitalize(label), naturalAgreements, valuedEvents,
                    100.0 * naturalAgreements / valuedEvents);
                System.out.printf("%s injections      : %d / %d  (%.1f%%)%n",
                    capitalize(label), injections, valuedEvents,
                    100.0 * injections / valuedEvents);
                System.out.printf("%s no-inject dis.  : %d / %d  (%.1f%%)%n",
                    capitalize(label), noInjectDisagreements, valuedEvents,
                    100.0 * noInjectDisagreements / valuedEvents);
            }
        }

        private static String capitalize(String s) {
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
