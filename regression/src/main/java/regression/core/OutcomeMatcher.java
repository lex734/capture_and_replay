package regression.core;

import regression.annotations.Outcome;
import regression.annotations.OutcomeExpectation;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.regex.Pattern;

public final class OutcomeMatcher {

    private static final String OBS_PREFIX = "[OBS] ";

    private OutcomeMatcher() {}

    /**
     * Classifies a run result into an outcome bucket.
     *
     * <p>Exception exits and timeouts are FORBIDDEN without pattern matching.
     * On a clean exit, the {@code [OBS] <value>} line from stdout is matched
     * against the declared outcomes in order; first match wins.
     * No match → INTERESTING.
     */
    public static OutcomeExpectation classify(RunResult result, Outcome[] outcomes) {
        if (result.timedOut || !result.isCleanExit()) {
            return OutcomeExpectation.FORBIDDEN;
        }
        String obs = extractObs(result.stdout);
        if (obs == null) {
            return OutcomeExpectation.INTERESTING;
        }
        return matchObs(obs, outcomes);
    }

    /**
     * Classifies using only the observed value string (for replay results where
     * exception/timeout has already been checked separately).
     */
    public static OutcomeExpectation matchObs(String obs, Outcome[] outcomes) {
        for (Outcome outcome : outcomes) {
            for (String pattern : outcome.id()) {
                if (Pattern.compile(pattern).matcher(obs).matches()) {
                    return outcome.expect();
                }
            }
        }
        return OutcomeExpectation.INTERESTING;
    }

    /** Returns the value after {@code "[OBS] "} in stdout, or {@code null} if absent. */
    public static String extractObs(String stdout) {
        if (stdout == null) return null;
        try (BufferedReader br = new BufferedReader(new StringReader(stdout))) {
            String line, last = null;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(OBS_PREFIX)) {
                    last = line.substring(OBS_PREFIX.length());
                }
            }
            return last;
        } catch (Exception e) {
            return null;
        }
    }
}
