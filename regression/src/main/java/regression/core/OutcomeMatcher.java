package regression.core;

import regression.annotations.Outcome;
import regression.annotations.OutcomeExpectation;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class OutcomeMatcher {

    private static final String OBS_PREFIX = "[OBS] ";

    private OutcomeMatcher() {}

    /**
     * Classifies a run result.
     *
     * <p>Exception exits and timeouts are FORBIDDEN unconditionally. On a clean exit,
     * all {@code [OBS] id=value} lines are parsed and matched against {@code @Outcome}
     * annotations (by {@code id}, then by {@code value} regex). Outcomes are evaluated
     * in declaration order; first match wins across all observations. No match → INTERESTING.
     */
    public static OutcomeExpectation classify(RunResult result, Outcome[] outcomes) {
        if (result.timedOut || !result.isCleanExit()) {
            return OutcomeExpectation.FORBIDDEN;
        }
        Map<String, String> obs = parseObservations(result.stdout);
        if (obs.isEmpty()) {
            return OutcomeExpectation.INTERESTING;
        }
        return matchObservations(obs, outcomes);
    }

    /** Matches pre-parsed observations against the outcome list. */
    public static OutcomeExpectation matchObservations(Map<String, String> obs, Outcome[] outcomes) {
        for (Outcome outcome : outcomes) {
            String value = obs.get(outcome.id());
            if (value == null) continue;
            for (String pattern : outcome.value()) {
                if (Pattern.compile(pattern).matcher(value).matches()) {
                    return outcome.expect();
                }
            }
        }
        return OutcomeExpectation.INTERESTING;
    }

    /**
     * Parses all {@code [OBS] id=value} lines from stdout into a map.
     * If the same id appears more than once, the last value wins.
     */
    public static Map<String, String> parseObservations(String stdout) {
        Map<String, String> result = new HashMap<>();
        if (stdout == null) return result;
        try (BufferedReader br = new BufferedReader(new StringReader(stdout))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith(OBS_PREFIX)) continue;
                String entry = line.substring(OBS_PREFIX.length());
                int eq = entry.indexOf('=');
                if (eq <= 0) continue;
                result.put(entry.substring(0, eq), entry.substring(eq + 1));
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Returns the first observed value from stdout (convenience for single-observation tests). */
    public static String firstObsValue(String stdout) {
        Map<String, String> obs = parseObservations(stdout);
        return obs.isEmpty() ? null : obs.values().iterator().next();
    }
}
