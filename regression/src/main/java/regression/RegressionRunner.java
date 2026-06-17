package regression;

import regression.annotations.*;
import regression.core.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/** Capture phase: runs the target program N times and saves FORBIDDEN/INTERESTING traces. */
public final class RegressionRunner {

    private static final String REDUCED_TRACE = "trace-reduced.tsv";

    private RegressionRunner() {}

    public static void run(String targetClass, String captureAgentJar,
                           String appJar, String regressionJar,
                           Path traceStore, int runsOverride) throws Exception {

        URLClassLoader appLoader = new URLClassLoader(
            new URL[]{Paths.get(appJar).toUri().toURL()},
            RegressionRunner.class.getClassLoader());
        Class<?> cls = appLoader.loadClass(targetClass);
        RegressionTest config = cls.getAnnotation(RegressionTest.class);
        if (config == null) {
            throw new IllegalArgumentException(targetClass + " is not annotated with @RegressionTest");
        }
        Outcome[] outcomes = resolveOutcomes(cls);
        int runs = runsOverride > 0 ? runsOverride : config.runs();
        long timeout = config.captureTimeoutMs();

        String simpleName = cls.getSimpleName();
        TraceStore store = new TraceStore(traceStore, simpleName);
        store.clear();

        Path workRoot = Files.createTempDirectory("regression-capture-");
        try {
            int acceptable = 0, forbidden = 0, interesting = 0, failed = 0, saved = 0;

            System.out.printf("=== Regression Capture: %s (%d runs) ===%n", simpleName, runs);

            for (int i = 0; i < runs; i++) {
                Path workDir = workRoot.resolve("run_" + i);
                Files.createDirectories(workDir);

                RunResult result;
                try {
                    result = ProcessRunner.runCapture(
                        captureAgentJar, appJar, regressionJar, targetClass, workDir, timeout);
                } catch (IOException | InterruptedException e) {
                    System.out.printf("  Run %3d/%d: launch error — %s%n", i + 1, runs, e.getMessage());
                    failed++;
                    continue;
                }

                if (!Files.exists(workDir.resolve(REDUCED_TRACE))) {
                    System.out.printf("  Run %3d/%d: no trace produced (%s)%n",
                        i + 1, runs, result.timedOut ? "timeout" : "exit " + result.exitCode);
                    failed++;
                    continue;
                }

                OutcomeExpectation bucket = OutcomeMatcher.classify(result, outcomes);
                Map<String, String> obs = OutcomeMatcher.parseObservations(result.stdout);
                String obsLabel = obs.isEmpty()
                    ? (result.timedOut ? "TIMEOUT" : "exception")
                    : formatObs(obs);

                switch (bucket) {
                    case ACCEPTABLE:  acceptable++;  break;
                    case FORBIDDEN:   forbidden++;   break;
                    case INTERESTING: interesting++; break;
                }

                if (bucket != OutcomeExpectation.ACCEPTABLE) {
                    String hash = TraceStore.sha256(workDir.resolve(REDUCED_TRACE));
                    if (!store.contains(hash)) {
                        Outcome matched = findMatchedOutcome(obs, outcomes);
                        String desc = matched != null ? matched.desc() : "";
                        store.save(hash, workDir, bucket, obsLabel, desc);
                        saved++;
                        System.out.printf("  Run %3d/%d: %-12s obs=%-14s [SAVED %s]%n",
                            i + 1, runs, bucket, obsLabel, hash.substring(0, 8));
                    } else {
                        System.out.printf("  Run %3d/%d: %-12s obs=%-14s [dup]%n",
                            i + 1, runs, bucket, obsLabel);
                    }
                } else {
                    System.out.printf("  Run %3d/%d: %-12s obs=%s%n",
                        i + 1, runs, bucket, obsLabel);
                }

                ProcessRunner.deleteRecursivelyIfExists(workDir);
            }

            System.out.printf("%nSummary: %d runs — ACCEPTABLE=%d  FORBIDDEN=%d  INTERESTING=%d  failed=%d%n",
                runs, acceptable, forbidden, interesting, failed);
            System.out.printf("Distinct traces saved: %d (FORBIDDEN+INTERESTING)%n", saved);

        } finally {
            ProcessRunner.deleteRecursivelyIfExists(workRoot);
        }
    }

    private static Outcome[] resolveOutcomes(Class<?> cls) {
        Outcomes container = cls.getAnnotation(Outcomes.class);
        if (container != null) return container.value();
        Outcome single = cls.getAnnotation(Outcome.class);
        if (single != null) return new Outcome[]{single};
        return new Outcome[0];
    }

    private static Outcome findMatchedOutcome(Map<String, String> obs, Outcome[] outcomes) {
        for (Outcome o : outcomes) {
            String value = obs.get(o.id());
            if (value == null) continue;
            for (String pattern : o.value()) {
                if (value.matches(pattern)) return o;
            }
        }
        return null;
    }

    static String formatObs(Map<String, String> obs) {
        if (obs.size() == 1) {
            Map.Entry<String, String> e = obs.entrySet().iterator().next();
            return e.getKey() + "=" + e.getValue();
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : obs.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
