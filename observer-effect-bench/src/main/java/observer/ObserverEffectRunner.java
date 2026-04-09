package observer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * Runs each JCStress observer-effect scenario in two modes — plain (no agent)
 * and with the capture agent — and compares the observed outcome distributions.
 *
 * For each scenario the runner looks for outcomes marked FORBIDDEN or
 * INTERESTING that appeared in the plain run but were suppressed (count = 0)
 * in the capture run.  Such outcomes are flagged as "HIDDEN by agent", which
 * is the observer effect: the capture agent's synchronization prevents the
 * hardware-level behaviour that produces the forbidden outcome.
 *
 * Usage (invoked by run.sh):
 *   java -cp target/jcstress.jar observer.ObserverEffectRunner \
 *        <capture-agent.jar> <jcstress.jar>
 */
public class ObserverEffectRunner {

    static final String[] SCENARIOS = {
        "observer.ScenarioStoreBuf",
        "observer.ScenarioDekker",
        "observer.ScenarioMessagePass",
        "observer.ScenarioLoadBuffer",
        "observer.ScenarioLostUpdate"
    };

    // Per-test wall-clock budget in seconds passed to JCStress via -time.
    static final int TEST_TIME_SECS = 5;
    // Hard timeout per JCStress subprocess (should be >> TEST_TIME_SECS).
    static final int TIMEOUT_SECS   = 120;

    // JCStress 0.16 prints per-fork outcome lines that look like:
    //
    //       0, 0       56    0.81%   Forbidden  Store buffering ...
    //       0, 1   498,231   98.5%  Acceptable  ...
    //
    // Multiple rows appear for the same state (one per fork); we accumulate counts.
    // Groups: (1) state  (2) count (may contain commas)  (3) Expectation (title case)
    static final Pattern OUTCOME_LINE = Pattern.compile(
        "^\\s+(\\S.*?)\\s{2,}([\\d,]+)\\s+[\\d.]+%\\s+(Forbidden|Acceptable|Interesting)\\b.*$"
    );

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ObserverEffectRunner <capture-agent.jar> <jcstress.jar>");
            System.exit(1);
        }

        Path captureJar = Paths.get(args[0]).toAbsolutePath();
        Path jcstressJar = Paths.get(args[1]).toAbsolutePath();

        if (!Files.exists(captureJar))
            throw new FileNotFoundException("capture agent not found: " + captureJar);
        if (!Files.exists(jcstressJar))
            throw new FileNotFoundException("jcstress jar not found: " + jcstressJar);

        System.out.println("Observer Effect Test Suite");
        System.out.println("Capture agent : " + captureJar);
        System.out.println("JCStress jar  : " + jcstressJar);
        System.out.printf ("Test duration : %ds per scenario per mode%n%n", TEST_TIME_SECS);

        Path workDir = Files.createTempDirectory("observer-effect-");
        try {
            for (String scenario : SCENARIOS) {
                runScenario(scenario, captureJar, jcstressJar, workDir);
            }
        } finally {
            deleteDir(workDir);
        }
    }

    // ── Per-scenario ──────────────────────────────────────────────────────────

    static void runScenario(String scenario, Path captureJar, Path jcstressJar, Path workDir)
            throws Exception {

        System.out.println("=".repeat(70));
        System.out.println("Scenario: " + scenario);
        System.out.println("=".repeat(70));

        // ── Plain run (no agent) ───────────────────────────────────────────
        System.out.println("  Running plain (no agent)...");
        String plainOut = runJCStress(null, jcstressJar, scenario, workDir);
        Map<String, long[]> plainMap = parseOutcomes(plainOut);

        // ── Capture run (with agent, JCStress infra excluded) ─────────────
        System.out.println("  Running with capture agent...");
        String captureOut = runJCStress(captureJar, jcstressJar, scenario, workDir);
        Map<String, long[]> captureMap = parseOutcomes(captureOut);

        // ── Print comparison ───────────────────────────────────────────────
        printComparison(scenario, plainMap, captureMap);
    }

    // ── JCStress invocation ───────────────────────────────────────────────────

    /**
     * Forks a JCStress subprocess for a single scenario.
     * If agentJar is non-null, passes it via -jvmArgs so it is attached to the
     * forked test JVMs (not the JCStress orchestrator JVM), with
     * exclude=org/openjdk/jcstress so JCStress's own infrastructure is not
     * instrumented.
     * Returns the combined stdout + stderr as a string.
     */
    static String runJCStress(Path agentJar, Path jcstressJar, String scenario, Path workDir)
            throws Exception {

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(jcstressJar.toString());
        cmd.add("-t");
        cmd.add(scenario);
        cmd.add("-time");
        cmd.add(String.valueOf(TEST_TIME_SECS));
        cmd.add("-v"); // always print per-fork outcome tables so we can parse counts
        if (agentJar != null) {
            cmd.add("-jvmArgs");
            cmd.add("-javaagent:" + agentJar + "=exclude=org/openjdk/jcstress");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        boolean done = p.waitFor(TIMEOUT_SECS, TimeUnit.SECONDS);

        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("JCStress timed out after " + TIMEOUT_SECS + "s for: " + scenario);
        }
        // JCStress exits with code 1 when forbidden/interesting outcomes are observed —
        // that is normal and expected.  Only hard failures (crash, missing class, etc.)
        // produce other non-zero codes, but we still want the output in all cases so
        // we can parse the outcome table.
        return new String(out);
    }

    // ── Output parsing ────────────────────────────────────────────────────────

    /**
     * Parses JCStress stdout for outcome lines and accumulates counts across all forks.
     * Returns a map: outcome-string → [total-count, expectation-code]
     * where expectation-code is 0=ACCEPTABLE, 1=INTERESTING, 2=FORBIDDEN.
     */
    static Map<String, long[]> parseOutcomes(String output) {
        Map<String, long[]> map = new LinkedHashMap<>();
        for (String line : output.split("\n")) {
            Matcher m = OUTCOME_LINE.matcher(line);
            if (!m.matches()) continue;
            String state  = m.group(1).trim();
            // Strip commas from formatted numbers like "6,788"
            long   count  = Long.parseLong(m.group(2).replace(",", ""));
            String expect = m.group(3);
            int    code   = expect.equalsIgnoreCase("Forbidden")   ? 2
                          : expect.equalsIgnoreCase("Interesting") ? 1
                          :                                          0;
            long[] existing = map.get(state);
            if (existing == null) {
                map.put(state, new long[]{count, code});
            } else {
                // Accumulate counts across forks; keep highest expectation code seen
                existing[0] += count;
                if (code > existing[1]) existing[1] = code;
            }
        }
        return map;
    }

    // ── Result printing ───────────────────────────────────────────────────────

    static void printComparison(String scenario,
                                Map<String, long[]> plain,
                                Map<String, long[]> capture) {

        // Collect all outcome keys from both runs
        Set<String> allOutcomes = new LinkedHashSet<>();
        allOutcomes.addAll(plain.keySet());
        allOutcomes.addAll(capture.keySet());

        if (allOutcomes.isEmpty()) {
            System.out.println("  (no outcome data parsed — check JCStress output above)");
            System.out.println();
            return;
        }

        String fmt = "  %-14s  %20s  %20s  %s%n";
        System.out.printf(fmt, "Outcome", "Plain (no agent)", "Capture (agent)", "Observer Effect");
        System.out.println("  " + "-".repeat(90));

        boolean anyHidden = false;

        for (String outcome : allOutcomes) {
            long[] pv = plain.getOrDefault(outcome,   new long[]{0, 0});
            long[] cv = capture.getOrDefault(outcome, new long[]{0, 0});

            long pCount = pv[0];
            long cCount = cv[0];
            int  code   = (int) Math.max(pv[1], cv[1]); // use highest expectation seen
            String label = code == 2 ? " [FORBIDDEN]"
                         : code == 1 ? " [INTERESTING]"
                         :             "";

            String effect = "";
            // Flag FORBIDDEN/INTERESTING outcomes present in plain but gone in capture
            if (code >= 1 && pCount > 0 && cCount == 0) {
                effect = "*** HIDDEN — agent suppresses hardware-only outcome";
                anyHidden = true;
            }

            System.out.printf(fmt,
                outcome,
                pCount + label,
                cCount + label,
                effect);
        }

        System.out.println();
        if (anyHidden) {
            System.out.println("  Observer effect DETECTED for " + scenario);
        } else {
            System.out.println("  No observer effect detected for " + scenario
                + " (forbidden outcomes may need more iterations, or may not appear on this hardware)");
        }
        System.out.println();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static void deleteDir(Path dir) throws IOException {
        Files.walk(dir)
             .sorted(Comparator.reverseOrder())
             .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
    }
}
