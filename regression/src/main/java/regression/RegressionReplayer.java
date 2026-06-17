package regression;

import regression.annotations.*;
import regression.core.*;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/** Regression phase: replays stored traces against the (possibly modified) program. */
public final class RegressionReplayer {

    private RegressionReplayer() {}

    public static void run(String targetClass, String replayAgentJar,
                           String appJar, String regressionJar,
                           Path traceStore) throws Exception {

        URLClassLoader appLoader = new URLClassLoader(
            new URL[]{java.nio.file.Paths.get(appJar).toUri().toURL()},
            RegressionReplayer.class.getClassLoader());
        Class<?> cls = appLoader.loadClass(targetClass);
        RegressionTest config = cls.getAnnotation(RegressionTest.class);
        if (config == null) {
            throw new IllegalArgumentException(targetClass + " is not annotated with @RegressionTest");
        }
        Outcome[] outcomes = resolveOutcomes(cls);
        long timeout = config.replayTimeoutMs();

        String simpleName = cls.getSimpleName();
        TraceStore store = new TraceStore(traceStore, simpleName);
        List<StoredTrace> traces = store.loadAll();

        if (traces.isEmpty()) {
            System.out.println("No stored traces found for " + simpleName + " in " + traceStore);
            return;
        }

        Path workRoot = Files.createTempDirectory("regression-replay-");
        try {
            System.out.printf("=== Regression Replay: %s (%d stored traces) ===%n",
                simpleName, traces.size());

            int fixed = 0, stillBuggy = 0, unknown = 0, changed = 0;

            for (int traceIdx = 0; traceIdx < traces.size(); traceIdx++) {
                StoredTrace trace = traces.get(traceIdx);
                Path workDir = workRoot.resolve(trace.sha256.substring(0, 8) + "_" + traceIdx);
                Files.createDirectories(workDir);

                // Copy stored trace files into work dir so the replay agent finds them
                for (String name : new String[]{"trace.bin", "trace-semantic.tsv", "trace-reduced.tsv"}) {
                    Path src = trace.dir.resolve(name);
                    if (Files.exists(src)) {
                        Files.copy(src, workDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                }

                Path fidelityProps = workDir.resolve("fidelity.properties");
                RunResult result;
                try {
                    result = ProcessRunner.runReplay(
                        replayAgentJar, appJar, regressionJar, targetClass,
                        workDir, trace.reducedTrace(), fidelityProps, timeout);
                } catch (IOException | InterruptedException e) {
                    System.out.printf("  [%s] %s → LAUNCH_ERROR: %s%n",
                        trace.sha256.substring(0, 8), trace.desc, e.getMessage());
                    unknown++;
                    continue;
                }

                FidelityResult fidelity = FidelityResult.read(fidelityProps);
                String verdict = classifyVerdict(result, fidelity, outcomes, trace.expect);
                String qualifier = fidelity.qualifier();

                switch (verdictBucket(verdict)) {
                    case "FIXED":       fixed++;      break;
                    case "STILL_BUGGY": stillBuggy++; break;
                    case "UNKNOWN":     unknown++;    break;
                    default:            changed++;    break;
                }

                Map<String, String> obsMap = OutcomeMatcher.parseObservations(result.stdout);
                String obsLabel = obsMap.isEmpty()
                    ? (result.timedOut ? "TIMEOUT" : "exception")
                    : RegressionRunner.formatObs(obsMap);

                System.out.printf("  [%s] %-30s was=%-12s obs=%-10s → %s %s%n",
                    trace.sha256.substring(0, 8),
                    truncate(trace.desc, 30),
                    trace.expect,
                    obsLabel,
                    verdict,
                    qualifier.isEmpty() ? "" : "[" + qualifier + "]");

                ProcessRunner.deleteRecursivelyIfExists(workDir);
            }

            System.out.printf("%nSummary: %d traces — FIXED=%d  STILL_BUGGY=%d  CHANGED=%d  UNKNOWN=%d%n",
                traces.size(), fixed, stillBuggy, changed, unknown);

        } finally {
            ProcessRunner.deleteRecursivelyIfExists(workRoot);
        }
    }

    /**
     * Derives the regression verdict using the fidelity flag priority table.
     *
     * Priority (first match wins):
     * 1. unreachable=true       → FIXED (interleaving path eliminated)
     * 2. unapplicable=true      → UNKNOWN [unapplicable]
     * 3. binding_conflict=true  → UNKNOWN [binding_conflict]
     * 4. unsupported=true       → UNKNOWN [unsupported]
     * 5. observable-based       → FIXED / STILL_BUGGY / CHANGED
     */
    private static String classifyVerdict(RunResult result, FidelityResult fidelity,
                                          Outcome[] outcomes, OutcomeExpectation originalExpect) {
        if (fidelity.unreachable)    return "FIXED";
        if (fidelity.unapplicable)   return "UNKNOWN";
        if (fidelity.bindingConflict) return "UNKNOWN";
        if (fidelity.unsupported)    return "UNKNOWN";

        OutcomeExpectation newOutcome = OutcomeMatcher.classify(result, outcomes);

        if (originalExpect == OutcomeExpectation.FORBIDDEN) {
            if (newOutcome == OutcomeExpectation.ACCEPTABLE)  return "FIXED";
            if (newOutcome == OutcomeExpectation.FORBIDDEN)   return "STILL_BUGGY";
            return "CHANGED";
        }
        if (originalExpect == OutcomeExpectation.INTERESTING) {
            if (newOutcome == OutcomeExpectation.ACCEPTABLE)  return "INTERESTING_FIXED";
            return "CHANGED";
        }
        return "CHANGED";
    }

    private static String verdictBucket(String verdict) {
        if (verdict.equals("FIXED") || verdict.equals("INTERESTING_FIXED")) return "FIXED";
        if (verdict.equals("STILL_BUGGY")) return "STILL_BUGGY";
        if (verdict.equals("UNKNOWN"))     return "UNKNOWN";
        return "CHANGED";
    }

    private static Outcome[] resolveOutcomes(Class<?> cls) {
        Outcomes container = cls.getAnnotation(Outcomes.class);
        if (container != null) return container.value();
        Outcome single = cls.getAnnotation(Outcome.class);
        if (single != null) return new Outcome[]{single};
        return new Outcome[0];
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---- Fidelity properties reader ----

    static final class FidelityResult {
        boolean unreachable;
        boolean unapplicable;
        boolean bindingConflict;
        boolean unsupported;
        boolean incomplete;
        boolean degraded;

        static FidelityResult read(Path propsFile) {
            FidelityResult r = new FidelityResult();
            if (!Files.exists(propsFile)) return r;
            Properties p = new Properties();
            try (Reader rd = Files.newBufferedReader(propsFile)) { p.load(rd); }
            catch (IOException ignored) { return r; }

            r.unreachable    = bool(p, "unreachable");
            r.unapplicable   = bool(p, "unapplicable");
            r.bindingConflict = bool(p, "binding_conflict");
            r.unsupported    = bool(p, "unsupported");
            r.incomplete     = bool(p, "incomplete");
            r.degraded       = bool(p, "degraded");
            return r;
        }

        private static boolean bool(Properties p, String key) {
            return Boolean.parseBoolean(p.getProperty(key, "false"));
        }

        String qualifier() {
            if (unreachable)    return "unreachable";
            if (unapplicable)   return "unapplicable";
            if (bindingConflict) return "binding_conflict";
            if (unsupported)    return "unsupported";
            if (incomplete)     return "incomplete";
            if (degraded)       return "degraded";
            return "";
        }
    }
}
