package overhead;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Measures the wall-clock overhead added by the capture and replay agents,
 * and optionally by Fray (when FRAY_HOME and FRAY_VERSION env vars are set).
 *
 * For each workload class, runs MEASURE rounds in three modes:
 *   1. Baseline  - plain JVM, no agent
 *   2. Capture   - with trace-capture-agent.jar attached
 *   3. Replay    - with trace-replay-agent.jar attached (reads the trace produced
 *                  by a dedicated capture run that precedes the replay measurements)
 *
 * When Fray is configured, two additional modes are measured:
 *   4. FrayBase  - Fray's patched JDK, no agents (fair baseline for Fray)
 *   5. Fray      - Fray's patched JDK + JVMTI + instrumentation agent, random scheduler
 *
 * Prints a summary table with mean +- std-dev in milliseconds and the
 * overhead ratio relative to the baseline.
 *
 * Usage (invoked by run.sh):
 *   java -cp overhead-bench.jar overhead.BenchmarkRunner \
 *        <capture-agent.jar> <replay-agent.jar> <benchmark-classpath> \
 *        [suite-file] [extra-jvm-arg...]
 */
public class BenchmarkRunner {

    static final String[] DEFAULT_WORKLOADS = {
        "overhead.WorkloadPlainCounter",
        "overhead.WorkloadAtomicCounter",
        "overhead.WorkloadVolatileWrite",
        "overhead.WorkloadSharedObject",
        "overhead.WorkloadArrayElement",
    };

    static final String[] INCOMPLETE_REPLAY_SIGNALS = {
        "[DIVERGENCE]",
        "Replay has structurally diverged",
        "valued event mismatch",
        "site mismatch",
        "object mismatch",
        "Incomplete replay",
    };
    static final Set<String> DEADLOCK_BENCHMARKS = new HashSet<>(Arrays.asList(
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Carter01Bad",
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Deadlock01Bad",
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Sync01Bad",
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Sync02Bad"
    ));
    // Benchmarks where timeout (deadlock/liveness failure) is the CLEAN outcome.
    // The bug manifests as a fast exit with a detectable signal; without the bug the program hangs.
    static final Set<String> DEADLOCK_CLEAN_BENCHMARKS = new HashSet<>(Arrays.asList(
        "cmu.pasta.fray.benchmark.sctbench.cs.origin.Phase01Bad"
    ));

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: BenchmarkRunner <capture-agent.jar> <replay-agent.jar> <benchmark-classpath> [suite-file] [extra-jvm-arg...]");
            System.exit(1);
        }
        int warmup = requiredIntProperty("overhead.warmup");
        int measure = requiredIntProperty("overhead.measure");
        int timeoutSeconds = requiredIntProperty("overhead.timeout.seconds");
        int measureWindowSeconds = requiredIntProperty("overhead.measure.window.seconds");
        if (warmup < 0 || measure <= 0 || timeoutSeconds <= 0 || measureWindowSeconds <= 0) {
            throw new IllegalArgumentException(
                "Invalid config: warmup>=0, measure>0, timeout.seconds>0, measure.window.seconds>0 required");
        }

        Path captureJar = Paths.get(args[0]).toAbsolutePath();
        Path replayJar  = Paths.get(args[1]).toAbsolutePath();
        String classpath = absolutizeClasspath(args[2]);
        String classpathDisplay = classpath;

        int argIndex = 3;
        Path suiteFile = null;
        if (args.length > 3) {
            Path candidate = Paths.get(args[3]);
            if (Files.exists(candidate)) {
                suiteFile = candidate.toAbsolutePath();
                argIndex = 4;
            }
        }

        List<String> extraJvmArgs = new ArrayList<>();
        for (int i = argIndex; i < args.length; i++) {
            extraJvmArgs.add(args[i]);
        }

        List<String> workloads = (suiteFile == null)
            ? Arrays.asList(DEFAULT_WORKLOADS)
            : loadWorkloads(suiteFile);

        checkExists(captureJar, "capture agent");
        checkExists(replayJar,  "replay agent");

        // Fray is optional: enabled when FRAY_HOME and FRAY_VERSION are set.
        FrayPaths fray = null;
        String frayHome    = System.getenv("FRAY_HOME");
        String frayVersion = System.getenv("FRAY_VERSION");
        if (frayHome != null && !frayHome.isBlank() && frayVersion != null && !frayVersion.isBlank()) {
            fray = new FrayPaths(frayHome.trim(), frayVersion.trim());
            System.out.println("Fray home     : " + fray.home);
            System.out.println("Fray version  : " + fray.version);
        }

        System.out.println("Capture agent : " + captureJar);
        System.out.println("Replay agent  : " + replayJar);
        System.out.println("Classpath     : " + classpathDisplay);
        System.out.println("Workload src  : " + (suiteFile == null ? "built-in defaults" : suiteFile));
        System.out.println("Workloads     : " + workloads.size());
        if (!extraJvmArgs.isEmpty()) {
            System.out.println("Extra JVM args: " + String.join(" ", extraJvmArgs));
        }
        System.out.printf("Warmup rounds : %d  |  Replay rounds: %d  |  Window: %ds  |  Timeout: %ds%n%n",
            warmup, measure, measureWindowSeconds, timeoutSeconds);

        Path workDir = Files.createTempDirectory("overhead-bench-");
        try {
            List<RowResult> rows = new ArrayList<>();
            for (int i = 0; i < workloads.size(); i++) {
                String workload = workloads.get(i);
                System.out.printf("[%d/%d] Running %s%n", i + 1, workloads.size(), workload);
                rows.add(benchmarkWorkload(
                    workload, captureJar, replayJar, classpath, extraJvmArgs, fray,
                    workDir, warmup, measure, timeoutSeconds, measureWindowSeconds));
            }
            System.out.println();
            printSection("Buggy Outcomes", rows, true,  fray != null);
            System.out.println();
            printSection("Clean Outcomes", rows, false, fray != null);
        } finally {
            deleteDir(workDir);
        }
    }

    // -- Per-workload benchmark ---------------------------------------------

    static final String TRACE_BIN      = "trace.bin";
    static final String TRACE_REDUCED  = "trace-reduced.tsv";
    static final String TRACE_SEMANTIC = "trace-semantic.tsv";

    static RowResult benchmarkWorkload(
            String cls, Path captureJar, Path replayJar, String classpath, List<String> extraJvmArgs,
            FrayPaths fray, Path workDir,
            int warmup, int measure, int timeoutSeconds, int measureWindowSeconds)
            throws Exception {

        List<String> baseCmd    = javaCmd(null,       classpath, cls, extraJvmArgs);
        List<String> captureCmd = javaCmd(captureJar, classpath, cls, extraJvmArgs);
        List<String> replayCmd  = javaCmd(replayJar,  classpath, cls, extraJvmArgs);
        Path workloadDir = workDir.resolve(safeWorkloadName(cls));
        Files.createDirectories(workloadDir);
        deleteTraceFiles(workloadDir);

        // -- Baseline ------------------------------------------------------
        for (int i = 0; i < warmup; i++) run(baseCmd, workloadDir, timeoutSeconds);
        WindowResult baseWindow = measureRunsForDuration(
            cls, baseCmd, workloadDir, timeoutSeconds, measureWindowSeconds);

        // -- Capture + Replay (buggy) -------------------------------------
        for (int i = 0; i < warmup; i++) run(captureCmd, workloadDir, timeoutSeconds);
        OutcomeResult buggy = measureCaptureAndReplayForDuration(
            cls, captureCmd, replayCmd, captureJar, workloadDir, timeoutSeconds, measureWindowSeconds, measure, true);

        // -- Capture + Replay (clean) -------------------------------------
        deleteTraceFiles(workloadDir);
        for (int i = 0; i < warmup; i++) run(captureCmd, workloadDir, timeoutSeconds);
        OutcomeResult clean = measureCaptureAndReplayForDuration(
            cls, captureCmd, replayCmd, captureJar, workloadDir, timeoutSeconds, measureWindowSeconds, measure, false);

        buggy = buggy.withBaseline(baseWindow.buggyMs, baseWindow.buggyRuns, baseWindow.attempts);
        clean = clean.withBaseline(baseWindow.cleanMs, baseWindow.cleanRuns, baseWindow.attempts);

        // -- Fray (optional) ----------------------------------------------
        List<Long> frayBuggyBaseMs = new ArrayList<>();
        List<Long> frayCleanBaseMs = new ArrayList<>();
        List<Long> frayBuggyMs     = new ArrayList<>();
        List<Long> frayCleanMs     = new ArrayList<>();

        if (fray != null) {
            Path configFile = workloadDir.resolve("fray-config.json");
            writeConfigJson(configFile, cls, classpath);

            List<String> frayBaseCmd = frayJdkCmd(fray, classpath, cls, extraJvmArgs);
            List<String> frayCmd     = frayFullCmd(fray, classpath, cls, configFile, timeoutSeconds, extraJvmArgs, workloadDir);

            for (int i = 0; i < warmup; i++) run(frayBaseCmd, workloadDir, timeoutSeconds);
            WindowResult frayBaseWindow = measureRunsForDuration(
                cls, frayBaseCmd, workloadDir, timeoutSeconds, measureWindowSeconds);

            for (int i = 0; i < warmup; i++) run(frayCmd, workloadDir, timeoutSeconds);
            WindowResult frayFullWindow = measureRunsForDuration(
                cls, frayCmd, workloadDir, timeoutSeconds, measureWindowSeconds);

            frayBuggyBaseMs = frayBaseWindow.buggyMs;
            frayCleanBaseMs = frayBaseWindow.cleanMs;
            frayBuggyMs     = frayFullWindow.buggyMs;
            frayCleanMs     = frayFullWindow.cleanMs;
        }

        return new RowResult(cls, buggy, clean, frayBuggyBaseMs, frayCleanBaseMs, frayBuggyMs, frayCleanMs);
    }

    // -- Fray support -------------------------------------------------------

    static final class FrayPaths {
        final String home;
        final String version;

        FrayPaths(String home, String version) {
            this.home = home;
            this.version = version;
        }

        String javaBin()    { return home + "/result/java-inst-jdk21/bin/java"; }
        String jvmtiAgent() { return home + "/result/native-libs/libjvmti.so"; }
        String instrAgent() { return home + "/result/libs/fray-instrumentation-agent-" + version + ".jar"; }
        String coreJar()    { return home + "/result/libs/fray-core-" + version + ".jar"; }
    }

    // Fray JDK, no agents — the fair baseline for Fray overhead ratios.
    static List<String> frayJdkCmd(FrayPaths fray, String classpath, String cls, List<String> extraJvmArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(fray.javaBin());
        cmd.add("-ea");
        cmd.addAll(extraJvmArgs);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(cls);
        return cmd;
    }

    // Fray JDK + JVMTI agent + instrumentation agent + Fray core, random scheduler, one iteration.
    static List<String> frayFullCmd(FrayPaths fray, String classpath, String cls,
            Path configFile, int timeoutSeconds, List<String> extraJvmArgs, Path workloadDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(fray.javaBin());
        cmd.add("-ea");
        cmd.add("-agentpath:" + fray.jvmtiAgent());
        cmd.add("-javaagent:" + fray.instrAgent());
        // Opens required by Fray's bytecode instrumentation.
        cmd.addAll(Arrays.asList(
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED"
        ));
        cmd.addAll(extraJvmArgs);
        cmd.add("-cp");
        cmd.add(fray.coreJar());
        cmd.add("org.pastalab.fray.core.MainKt");
        cmd.add("--run-config");
        cmd.add("json");
        cmd.add("--config-path");
        cmd.add(configFile.toString());
        cmd.add("-o");
        cmd.add(workloadDir.resolve("fray-report").toString());
        cmd.add("--iter");
        cmd.add("1");
        cmd.add("--timeout");
        cmd.add(String.valueOf(timeoutSeconds));
        cmd.add("--scheduler=random");
        return cmd;
    }

    static void writeConfigJson(Path file, String cls, String classpath) throws IOException {
        String[] parts = classpath.split(File.pathSeparator);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n    \"executor\": {\n");
        sb.append("        \"clazz\": \"").append(cls).append("\",\n");
        sb.append("        \"method\": \"main\",\n");
        sb.append("        \"args\": [],\n");
        sb.append("        \"classpaths\": [");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(parts[i].replace("\\", "\\\\")).append("\"");
        }
        sb.append("],\n");
        sb.append("        \"properties\": {}\n    },\n");
        sb.append("    \"ignore_unhandled_exceptions\": false,\n");
        sb.append("    \"interleave_memory_ops\": false,\n");
        sb.append("    \"max_scheduled_step\": -1,\n");
        sb.append("    \"timed_wait_wait_inf\": false\n}\n");
        Files.writeString(file, sb.toString());
    }

    // -- Helpers -------------------------------------------------------------

    static List<String> javaCmd(Path agentJar, String classpath, String mainClass, List<String> extraJvmArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-ea");
        if (agentJar != null) cmd.add("-javaagent:" + agentJar);
        cmd.addAll(extraJvmArgs);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass);
        return cmd;
    }

    static List<String> loadWorkloads(Path suiteFile) throws IOException {
        List<String> workloads = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(suiteFile)) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                workloads.add(trimmed);
            }
        }
        if (workloads.isEmpty()) {
            throw new IllegalArgumentException("Suite file contains no workloads: " + suiteFile);
        }
        return workloads;
    }

    static List<Long> measureReplay(
            String cls, List<String> cmd, Path workDir, boolean bugOutcome,
            int measure, int timeoutSeconds) throws Exception {
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < measure; i++) {
            RunResult rr = run(cmd, workDir, timeoutSeconds);
            boolean bugObserved = hadBug(cls, rr);
            if (bugOutcome) {
                if (bugObserved) {
                    ms.add(rr.elapsedMs);
                }
                continue;
            }
            if (bugObserved) continue;
            if (!rr.completed) continue;
            if (hasIncompleteReplaySignal(rr.output)) continue;
            ms.add(rr.elapsedMs);
        }
        return ms;
    }

    static WindowResult measureRunsForDuration(
            String cls, List<String> cmd, Path workDir, int timeoutSeconds, int measureWindowSeconds) throws Exception {
        List<Long> buggyMs = new ArrayList<>();
        List<Long> cleanMs = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(measureWindowSeconds);
        int attempts = 0;
        int buggyRuns = 0;
        int cleanRuns = 0;
        while (attempts == 0 || System.nanoTime() < deadlineNanos) {
            RunResult rr = run(cmd, workDir, timeoutSeconds);
            attempts++;
            boolean bugObserved = hadBug(cls, rr);
            if (bugObserved) {
                buggyRuns++;
                buggyMs.add(rr.elapsedMs);
            } else if (rr.completed || isCleanTimeout(cls, rr)) {
                cleanRuns++;
                cleanMs.add(rr.elapsedMs);
            }
        }
        return new WindowResult(buggyMs, cleanMs, attempts, buggyRuns, cleanRuns);
    }

    // Grace period after SIGTERM: enough for JVM shutdown hooks to write trace files.
    static final int SHUTDOWN_GRACE_SECONDS = 30;

    /**
     * Forks a subprocess and returns its wall-clock time in milliseconds.
     * stderr is merged into stdout; all output is discarded on success.
     *
     * When the test timeout expires, SIGTERM is sent rather than SIGKILL so that
     * JVM shutdown hooks (trace flush, reduced-trace write) run cleanly even for
     * deadlocked processes. SIGKILL is only used as a last resort if the process
     * does not exit within SHUTDOWN_GRACE_SECONDS after SIGTERM.
     */
    static RunResult run(List<String> cmd, Path workDir, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        long t0 = System.nanoTime();
        Process p = pb.start();
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        Thread drainer = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(outBuf);
            } catch (IOException ignored) {
            }
        }, "overhead-bench-output-drainer");
        drainer.setDaemon(true);
        drainer.start();

        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        if (!done) {
            // SIGTERM lets JVM shutdown hooks write trace-reduced.tsv and flush trace.bin.
            p.destroy();
            boolean cleanExit = p.waitFor(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
            if (!cleanExit) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            drainer.join(1000);
            return new RunResult(false, true, -1, elapsedMs, outBuf.toString());
        }

        drainer.join(1000);
        String out = outBuf.toString();
        int exitCode = p.exitValue();
        return new RunResult(exitCode == 0, false, exitCode, elapsedMs, out);
    }

    static void printSection(String title, List<RowResult> rows, boolean bugOutcome, boolean hasFray) {
        System.out.println(title);
        printHeader(bugOutcome, hasFray);
        for (RowResult row : rows) {
            OutcomeResult outcome = bugOutcome ? row.buggy : row.clean;
            List<Long> frayBaseMs = bugOutcome ? row.frayBuggyBaseMs : row.frayCleanBaseMs;
            List<Long> frayMs     = bugOutcome ? row.frayBuggyMs     : row.frayCleanMs;
            if (hasFray) {
                System.out.printf(
                    "%-28s  %16s  %16s  %16s  %16s  %16s  %10s  %10s  %8s  %8s  %8s%n",
                    row.name,
                    formatStats(outcome.baseMs),
                    formatStats(outcome.capMs),
                    formatReplayStats(outcome),
                    formatStats(frayBaseMs),
                    formatStats(frayMs),
                    formatCount(outcome.baseRuns, outcome.baseAttempts),
                    formatCount(outcome.capRuns, outcome.capAttempts),
                    formatRatio(outcome.capMs, outcome.baseMs),
                    formatRatio(outcome.repMs, outcome.baseMs),
                    formatRatio(frayMs, frayBaseMs));
            } else {
                System.out.printf(
                    "%-28s  %16s  %16s  %16s  %10s  %10s  %8s  %8s%n",
                    row.name,
                    formatStats(outcome.baseMs),
                    formatStats(outcome.capMs),
                    formatReplayStats(outcome),
                    formatCount(outcome.baseRuns, outcome.baseAttempts),
                    formatCount(outcome.capRuns, outcome.capAttempts),
                    formatRatio(outcome.capMs, outcome.baseMs),
                    formatRatio(outcome.repMs, outcome.baseMs));
            }
        }
    }

    static void printHeader(boolean bugOutcome, boolean hasFray) {
        String plainLabel = bugOutcome ? "PlainBug" : "PlainClean";
        String capLabel   = bugOutcome ? "CapBug"   : "CapClean";
        if (hasFray) {
            System.out.printf(
                "%-28s  %16s  %16s  %16s  %16s  %16s  %10s  %10s  %8s  %8s  %8s%n",
                "Workload", "Baseline (ms)", "Capture (ms)", "Replay (ms)",
                "FrayBase (ms)", "Fray (ms)",
                plainLabel, capLabel, "Cap/Base", "Rep/Base", "Fray/FrayBase");
            System.out.println("-".repeat(175));
        } else {
            System.out.printf(
                "%-28s  %16s  %16s  %16s  %10s  %10s  %8s  %8s%n",
                "Workload", "Baseline (ms)", "Capture (ms)", "Replay (ms)",
                plainLabel, capLabel, "Cap/Base", "Rep/Base");
            System.out.println("-".repeat(127));
        }
    }

    static boolean hasIncompleteReplaySignal(String out) {
        for (String signal : INCOMPLETE_REPLAY_SIGNALS) {
            if (out.contains(signal)) return true;
        }
        return false;
    }

    static boolean hadBug(String cls, RunResult rr) {
        return hasBugSignal(rr.output) || (rr.timedOut && DEADLOCK_BENCHMARKS.contains(cls));
    }

    static boolean isCleanTimeout(String cls, RunResult rr) {
        return rr.timedOut && DEADLOCK_CLEAN_BENCHMARKS.contains(cls);
    }

    static boolean hasBugSignal(String out) {
        return out.contains("AssertionError")
            || out.contains("Bug Found!")
            || out.contains("Bug found!")
            || out.contains("Deadlock detected")
            || out.contains("RuntimeException: deadlock");
    }

    static int requiredIntProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required system property: " + key);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer system property " + key + ": " + value, e);
        }
    }

    static double mean(List<Long> vals) {
        long sum = 0;
        for (long v : vals) sum += v;
        return (double) sum / vals.size();
    }

    static double std(List<Long> vals) {
        double m = mean(vals), s = 0;
        for (long v : vals) s += (v - m) * (v - m);
        return Math.sqrt(s / vals.size());
    }

    static String formatStats(List<Long> vals) {
        if (vals.isEmpty()) return String.format("%16s", "N/A");
        return String.format("%7.1f +- %6.1f", mean(vals), std(vals));
    }

    static String formatCount(int runs, int attempts) {
        return runs + "/" + attempts;
    }

    static String formatRatio(List<Long> numerator, List<Long> denominator) {
        if (numerator.isEmpty() || denominator.isEmpty()) return "N/A";
        return String.format("%.2fx", mean(numerator) / mean(denominator));
    }

    static String formatReplayStats(OutcomeResult outcome) {
        if (outcome.replayStatus != null) {
            return String.format("%16s", outcome.replayStatus);
        }
        return formatStats(outcome.repMs);
    }

    static OutcomeResult measureCaptureAndReplayForDuration(
            String cls, List<String> captureCmd, List<String> replayCmd, Path captureJar, Path workDir,
            int timeoutSeconds, int measureWindowSeconds, int replayMeasureCap, boolean bugOutcome) throws Exception {
        List<Long> capMs = new ArrayList<>();
        List<Long> repMs = new ArrayList<>();
        String replayStatus = "N/A";
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(measureWindowSeconds);
        // Allow up to 10x the window to collect enough traces for benchmarks where the target
        // outcome is rare under the capture agent (e.g. Phase01Bad bugs ~40% with agent).
        long extendedDeadlineNanos = deadlineNanos + TimeUnit.SECONDS.toNanos((long) measureWindowSeconds * 9);
        int attempts = 0;
        int capRuns = 0;
        List<Path> traces = new ArrayList<>();
        boolean missingTrace = false;
        while (attempts == 0 || System.nanoTime() < deadlineNanos ||
               (traces.size() < replayMeasureCap && System.nanoTime() < extendedDeadlineNanos)) {
            deleteTraceFiles(workDir);
            RunResult captureResult = run(captureCmd, workDir, timeoutSeconds);
            attempts++;
            boolean captureBugObserved = hadBug(cls, captureResult);
            boolean captureMatches = bugOutcome ? captureBugObserved
                : (!captureBugObserved && (captureResult.completed || isCleanTimeout(cls, captureResult)));
            if (!captureMatches) {
                continue;
            }
            capRuns++;
            capMs.add(captureResult.elapsedMs);
            if (Files.exists(workDir.resolve(TRACE_BIN))) {
                runReductionIfNeeded(captureJar, workDir, timeoutSeconds + SHUTDOWN_GRACE_SECONDS);
            }
            if (!Files.exists(workDir.resolve(TRACE_BIN)) || !Files.exists(workDir.resolve(TRACE_REDUCED))) {
                missingTrace = true;
                continue;
            }
            if (traces.size() < replayMeasureCap) {
                String tag = "trace-" + safeWorkloadName(cls) + "-" + bugOutcome + "-" + capRuns;
                Path savedTrace = workDir.resolve(tag + ".bin");
                Path savedReduced = workDir.resolve(tag + "-reduced.tsv");
                Files.copy(workDir.resolve(TRACE_BIN),     savedTrace,   StandardCopyOption.REPLACE_EXISTING);
                Files.copy(workDir.resolve(TRACE_REDUCED), savedReduced, StandardCopyOption.REPLACE_EXISTING);
                traces.add(savedTrace);
            }
        }

        boolean sawReplayMismatch = false;
        for (Path trace : traces) {
            String reducedName = trace.getFileName().toString().replace(".bin", "-reduced.tsv");
            Path reduced = trace.getParent().resolve(reducedName);
            for (int i = 0; i < replayMeasureCap; i++) {
                Files.copy(trace,   workDir.resolve(TRACE_BIN),     StandardCopyOption.REPLACE_EXISTING);
                Files.copy(reduced, workDir.resolve(TRACE_REDUCED), StandardCopyOption.REPLACE_EXISTING);
                RunResult replayResult = run(replayCmd, workDir, timeoutSeconds);
                boolean replayBugObserved = hadBug(cls, replayResult);
                boolean replayMatches = bugOutcome
                    ? (replayBugObserved && !hasIncompleteReplaySignal(replayResult.output))
                    : (!replayBugObserved && (replayResult.completed || isCleanTimeout(cls, replayResult))
                        && !hasIncompleteReplaySignal(replayResult.output));
                if (replayMatches) {
                    repMs.add(replayResult.elapsedMs);
                    replayStatus = null;
                } else {
                    sawReplayMismatch = true;
                }
            }
        }

        if (replayStatus != null) {
            if (!traces.isEmpty() && sawReplayMismatch) {
                replayStatus = "INCOMPLETE";
            } else if (!traces.isEmpty()) {
                replayStatus = repMs.isEmpty() ? "INCOMPLETE" : null;
            } else if (missingTrace) {
                replayStatus = "NO_TRACE";
            }
        }
        return new OutcomeResult(new ArrayList<>(), 0, 0, capMs, capRuns, attempts, repMs, replayStatus);
    }

    static void checkExists(Path p, String label) throws FileNotFoundException {
        if (!Files.exists(p)) {
            throw new FileNotFoundException(label + " not found: " + p);
        }
    }

    static String absolutizeClasspath(String classpath) {
        String[] parts = classpath.split(File.pathSeparator);
        List<String> rendered = new ArrayList<>();
        for (String part : parts) {
            Path p = Paths.get(part);
            rendered.add(p.isAbsolute() ? p.toString() : p.toAbsolutePath().toString());
        }
        return String.join(File.pathSeparator, rendered);
    }

    static String safeWorkloadName(String cls) {
        return cls.replace('.', '_');
    }

    static void deleteIfExists(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    static void deleteTraceFiles(Path workDir) throws IOException {
        Files.deleteIfExists(workDir.resolve(TRACE_BIN));
        Files.deleteIfExists(workDir.resolve(TRACE_REDUCED));
        Files.deleteIfExists(workDir.resolve(TRACE_SEMANTIC));
    }

    // Run trace reduction in a fresh JVM with ample heap so that large traces
    // (from long-running or deadlocked benchmarks) don't OOM the reduction.
    static void runReductionIfNeeded(Path captureJar, Path workDir, int timeoutSeconds) throws Exception {
        if (Files.exists(workDir.resolve(TRACE_REDUCED))) return;
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xmx2g");
        cmd.add("-cp");
        cmd.add(captureJar.toString());
        cmd.add("common.v1.TraceReducer");
        cmd.add(TRACE_REDUCED);
        cmd.add(TRACE_BIN);
        cmd.add(TRACE_SEMANTIC);
        run(cmd, workDir, timeoutSeconds);
    }

    static void deleteDir(Path dir) throws IOException {
        Files.walk(dir)
             .sorted(Comparator.reverseOrder())
             .forEach(p -> {
                 try {
                     Files.delete(p);
                 } catch (IOException ignored) {
                 }
             });
    }

    static final class RunResult {
        final boolean completed;
        final boolean timedOut;
        final int exitCode;
        final long elapsedMs;
        final String output;

        RunResult(boolean completed, boolean timedOut, int exitCode, long elapsedMs, String output) {
            this.completed = completed;
            this.timedOut = timedOut;
            this.exitCode = exitCode;
            this.elapsedMs = elapsedMs;
            this.output = output == null ? "" : output;
        }
    }

    static final class WindowResult {
        final List<Long> buggyMs;
        final List<Long> cleanMs;
        final int attempts;
        final int buggyRuns;
        final int cleanRuns;

        WindowResult(List<Long> buggyMs, List<Long> cleanMs, int attempts, int buggyRuns, int cleanRuns) {
            this.buggyMs = buggyMs;
            this.cleanMs = cleanMs;
            this.attempts = attempts;
            this.buggyRuns = buggyRuns;
            this.cleanRuns = cleanRuns;
        }
    }

    static final class OutcomeResult {
        final List<Long> baseMs;
        final int baseRuns;
        final int baseAttempts;
        final List<Long> capMs;
        final int capRuns;
        final int capAttempts;
        final List<Long> repMs;
        final String replayStatus;

        OutcomeResult(List<Long> baseMs, int baseRuns, int baseAttempts, List<Long> capMs, int capRuns, int capAttempts,
                List<Long> repMs, String replayStatus) {
            this.baseMs = baseMs;
            this.baseRuns = baseRuns;
            this.baseAttempts = baseAttempts;
            this.capMs = capMs;
            this.capRuns = capRuns;
            this.capAttempts = capAttempts;
            this.repMs = repMs;
            this.replayStatus = replayStatus;
        }

        OutcomeResult withBaseline(List<Long> newBaseMs, int newBaseRuns, int newBaseAttempts) {
            return new OutcomeResult(newBaseMs, newBaseRuns, newBaseAttempts, capMs, capRuns, capAttempts, repMs, replayStatus);
        }
    }

    static final class RowResult {
        final String name;
        final OutcomeResult buggy;
        final OutcomeResult clean;
        // Fray data: separate Fray-JDK baseline so the Fray/FrayBase ratio is fair.
        final List<Long> frayBuggyBaseMs;
        final List<Long> frayCleanBaseMs;
        final List<Long> frayBuggyMs;
        final List<Long> frayCleanMs;

        RowResult(String cls, OutcomeResult buggy, OutcomeResult clean,
                List<Long> frayBuggyBaseMs, List<Long> frayCleanBaseMs,
                List<Long> frayBuggyMs,     List<Long> frayCleanMs) {
            this.name = cls.substring(cls.lastIndexOf('.') + 1);
            this.buggy = buggy;
            this.clean = clean;
            this.frayBuggyBaseMs = frayBuggyBaseMs;
            this.frayCleanBaseMs = frayCleanBaseMs;
            this.frayBuggyMs     = frayBuggyMs;
            this.frayCleanMs     = frayCleanMs;
        }
    }
}
