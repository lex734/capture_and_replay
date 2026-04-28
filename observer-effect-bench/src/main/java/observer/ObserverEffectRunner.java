package observer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs JCStress scenarios in plain and capture modes and preserves raw artifacts.
 */
public class ObserverEffectRunner {
    static final DateTimeFormatter RUN_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    static final int TEST_TIME_SECS = intFromEnv("OBSERVER_TEST_TIME_SECS", 5);
    static final int TIMEOUT_SECS = intFromEnv("OBSERVER_TIMEOUT_SECS", Math.max(900, TEST_TIME_SECS * 120));
    static final int MAX_TESTS = intFromEnv("OBSERVER_MAX_TESTS", Integer.MAX_VALUE);

    static final Pattern TEST_NAME_LINE = Pattern.compile("^[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)+$");

    static String timeArgName = "-time";

    static final class RunResult {
        final boolean success;
        final Path runDir;
        final Path logFile;
        final String diagnostic;

        RunResult(boolean success, Path runDir, Path logFile, String diagnostic) {
            this.success = success;
            this.runDir = runDir;
            this.logFile = logFile;
            this.diagnostic = diagnostic;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ObserverEffectRunner <capture-agent.jar> <jcstress.jar> [scenario] [results-dir]");
            System.exit(1);
        }

        Path captureJar = Path.of(args[0]).toAbsolutePath();
        Path jcstressJar = Path.of(args[1]).toAbsolutePath();
        String scenarioFilter = args.length >= 3 ? args[2] : null;
        Path resultsRoot = args.length >= 4
            ? Path.of(args[3]).toAbsolutePath()
            : Path.of("results", "run-" + RUN_TS.format(LocalDateTime.now())).toAbsolutePath();

        if (!Files.exists(captureJar)) {
            throw new IOException("capture agent not found: " + captureJar);
        }
        if (!Files.exists(jcstressJar)) {
            throw new IOException("jcstress jar not found: " + jcstressJar);
        }

        Files.createDirectories(resultsRoot);
        Path metaWorkDir = resultsRoot.resolve("_runner");
        Files.createDirectories(metaWorkDir);
        timeArgName = detectTimeArgName(jcstressJar, metaWorkDir);
        List<String> scenarios = selectScenarios(jcstressJar, metaWorkDir, scenarioFilter);

        System.out.println("Observer Effect Test Suite");
        System.out.println("Capture agent : " + captureJar);
        System.out.println("JCStress jar  : " + jcstressJar);
        System.out.printf("Test duration : %ds per test per mode%n%n", TEST_TIME_SECS);
        System.out.println("Results dir   : " + resultsRoot);
        System.out.println();

        int completedCount = 0;
        for (String scenario : scenarios) {
            try {
                if (runScenario(scenario, captureJar, jcstressJar, resultsRoot)) {
                    completedCount++;
                }
            } catch (Exception e) {
                System.out.println("  Scenario failed: " + scenario);
                System.out.println("  Reason: " + e.getMessage());
                System.out.println();
            }
        }

        System.out.println("=".repeat(70));
        System.out.printf("Summary: %d / %d scenarios completed and saved artifacts%n",
            completedCount, scenarios.size());
        System.out.println("=".repeat(70));
    }

    static boolean runScenario(String scenario, Path captureJar, Path jcstressJar, Path resultsRoot) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("Scenario: " + scenario);
        System.out.println("=".repeat(70));
        Path scenarioDir = resultsRoot.resolve(sanitizeForFile(scenario));
        Files.createDirectories(scenarioDir);
        System.out.println("  Artifact dir: " + scenarioDir);

        System.out.println("  Running plain (no agent)...");
        RunResult plain = runJCStress(null, jcstressJar, scenario, scenarioDir.resolve("plain"));

        System.out.println("  Running with capture agent...");
        RunResult capture = runJCStress(captureJar, jcstressJar, scenario, scenarioDir.resolve("capture"));

        System.out.println("  Plain artifacts  : " + plain.runDir);
        if (plain.diagnostic != null) {
            System.out.println("  [plain diagnostic] " + plain.diagnostic);
        }
        System.out.println("  Capture artifacts: " + capture.runDir);
        if (capture.diagnostic != null) {
            System.out.println("  [capture diagnostic] " + capture.diagnostic);
        }
        System.out.println();
        return plain.success && capture.success;
    }

    static RunResult runJCStress(Path agentJar, Path jcstressJar, String scenario, Path runDir) throws Exception {
        Files.createDirectories(runDir);
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(jcstressJar.toString());
        cmd.add("-t");
        cmd.add(scenario);
        cmd.add(timeArgName);
        cmd.add("-tb".equals(timeArgName) ? (TEST_TIME_SECS + "s") : String.valueOf(TEST_TIME_SECS));
        cmd.add("-v");

        if (agentJar != null) {
            String includePrefix = scenario.startsWith("org.openjdk.jcstress.samples.")
                ? "org/openjdk/jcstress/samples"
                : scenario.substring(0, Math.max(0, scenario.lastIndexOf('.'))).replace('.', '/');
            String traceFile = "trace-" + sanitizeForFile(scenario) + "-capture.bin";
            cmd.add("-jvmArgsPrepend");
            cmd.add("-javaagent:" + agentJar
                + "=exclude=org/openjdk/jcstress,include=" + includePrefix + ",trace=" + traceFile);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(runDir.toFile());
        pb.redirectErrorStream(true);
        Path logFile = runDir.resolve("jcstress.log");
        pb.redirectOutput(logFile.toFile());

        Process p = pb.start();
        boolean done = p.waitFor(TIMEOUT_SECS, TimeUnit.SECONDS);
        if (!done) {
            destroyProcessTree(p);
            String diagnostic = findDiagnosticLine(logFile);
            throw new RuntimeException("JCStress timed out after " + TIMEOUT_SECS + "s"
                + (diagnostic == null ? "" : "; diagnostic: " + diagnostic)
                + "; log: " + logFile);
        }

        String diagnostic = findDiagnosticLine(logFile);
        return new RunResult(p.exitValue() == 0, runDir, logFile, diagnostic);
    }

    static List<String> selectScenarios(Path jcstressJar, Path workDir, String scenarioFilter) throws Exception {
        if (scenarioFilter != null && !scenarioFilter.isBlank()) {
            return Collections.singletonList(scenarioFilter.trim());
        }

        List<String> available = listTests(jcstressJar, workDir);
        List<String> sampleTests = new ArrayList<>();
        for (String test : available) {
            if (isJmmOrConcurrencySample(test)) {
                sampleTests.add(test);
            }
        }

        if (sampleTests.size() > MAX_TESTS) {
            return sampleTests.subList(0, MAX_TESTS);
        }
        return sampleTests;
    }

    static List<String> listTests(Path jcstressJar, Path workDir) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(jcstressJar.toString());
        cmd.add("-l");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        boolean done = p.waitFor(TIMEOUT_SECS, TimeUnit.SECONDS);
        if (!done) {
            destroyProcessTree(p);
            throw new RuntimeException("JCStress -l timed out after " + TIMEOUT_SECS + "s");
        }

        List<String> tests = new ArrayList<>();
        for (String line : new String(out).split("\n")) {
            String s = line.trim();
            if (TEST_NAME_LINE.matcher(s).matches()) {
                tests.add(s);
            }
        }
        return tests;
    }

    static boolean isJmmOrConcurrencySample(String testName) {
        if (!testName.startsWith("org.openjdk.jcstress.samples.")) return false;
        return !testName.startsWith("org.openjdk.jcstress.samples.api.");
    }

    static String findDiagnosticLine(Path outputFile) throws IOException {
        Pattern p = Pattern.compile(
            "(SocketException|No matching tests|Exception in thread|FATAL:|Caused by:|Error:)",
            Pattern.CASE_INSENSITIVE);
        String best = null;
        try (var br = Files.newBufferedReader(outputFile)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (p.matcher(line).find()) {
                    String s = line.trim();
                    if (s.equals("Error: Could not create the Java Virtual Machine.")) {
                        continue;
                    }
                    if (s.equals("Error: A fatal exception has occurred. Program will exit.")) {
                        continue;
                    }
                    if (s.contains("SocketException")) {
                        return s;
                    }
                    if (s.contains("No matching tests")) {
                        return s;
                    }
                    if (best == null) {
                        best = s;
                    } else if (!best.contains("Exception in thread") && s.contains("Exception in thread")) {
                        best = s;
                    }
                }
            }
        }
        return best;
    }

    static String sanitizeForFile(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    static String detectTimeArgName(Path jcstressJar, Path workDir) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(jcstressJar.toString());
        cmd.add("-time");
        cmd.add("1");
        cmd.add("-l");
        cmd.add("-t");
        cmd.add("__observer_probe_no_tests__");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        boolean done = p.waitFor(TIMEOUT_SECS, TimeUnit.SECONDS);
        if (!done) {
            destroyProcessTree(p);
            return "-time";
        }

        String s = new String(out);
        if (s.contains("-time option is not supported anymore, please use -tb")) {
            return "-tb";
        }
        return "-time";
    }

    static void destroyProcessTree(Process p) {
        try {
            ProcessHandle h = p.toHandle();
            h.descendants().forEach(ph -> {
                try {
                    ph.destroyForcibly();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        try {
            p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    static int intFromEnv(String key, int defaultValue) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
