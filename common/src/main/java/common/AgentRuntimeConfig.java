package common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AgentRuntimeConfig {
    private final ReplayMode mode;
    private final String extraExclude;
    private final List<String> applicationPrefixes;

    public AgentRuntimeConfig(ReplayMode mode, String extraExclude, List<String> applicationPrefixes) {
        this.mode = mode == null ? ReplayMode.BALANCED : mode;
        this.extraExclude = normalizePrefix(extraExclude);
        this.applicationPrefixes = Collections.unmodifiableList(normalizePrefixes(applicationPrefixes));
    }

    public static AgentRuntimeConfig parse(String agentArgs) {
        ReplayMode mode = ReplayMode.BALANCED;
        String extraExclude = null;
        List<String> appPrefixes = new ArrayList<>();
        if (agentArgs != null && !agentArgs.trim().isEmpty()) {
            for (String part : agentArgs.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("mode=")) {
                    mode = ReplayMode.fromString(trimmed.substring("mode=".length()));
                } else if (trimmed.startsWith("exclude=")) {
                    extraExclude = trimmed.substring("exclude=".length());
                } else if (trimmed.startsWith("app=")) {
                    addPrefixes(appPrefixes, trimmed.substring("app=".length()));
                }
            }
        }
        return new AgentRuntimeConfig(mode, extraExclude, appPrefixes);
    }

    private static void addPrefixes(List<String> out, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        for (String value : raw.split(":")) {
            String normalized = normalizePrefix(value);
            if (normalized != null && !out.contains(normalized)) {
                out.add(normalized);
            }
        }
    }

    private static List<String> normalizePrefixes(List<String> prefixes) {
        List<String> normalized = new ArrayList<>();
        if (prefixes == null) {
            return normalized;
        }
        for (String prefix : prefixes) {
            String value = normalizePrefix(prefix);
            if (value != null && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private static String normalizePrefix(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replace('.', '/');
    }

    public ReplayMode mode() {
        return mode;
    }

    public String extraExclude() {
        return extraExclude;
    }

    public List<String> applicationPrefixes() {
        return applicationPrefixes;
    }

    public boolean isApplicationClass(String className) {
        if (className == null) {
            return false;
        }
        if (applicationPrefixes.isEmpty()) {
            return true;
        }
        for (String prefix : applicationPrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAlwaysExcluded(String className) {
        if (className == null) {
            return true;
        }
        return className.startsWith("instr/")
                || className.startsWith("common/")
                || className.startsWith("capture/")
                || className.startsWith("replay/")
                || className.startsWith("java/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || (extraExclude != null && className.startsWith(extraExclude));
    }

    public boolean shouldInstrumentClass(String className) {
        if (isAlwaysExcluded(className)) {
            return false;
        }
        boolean applicationClass = isApplicationClass(className);
        switch (mode) {
            case SAFE:
                return applicationClass;
            case BALANCED:
                return applicationClass || isDependencyCandidate(className);
            case AGGRESSIVE:
                return true;
            default:
                return applicationClass;
        }
    }

    public boolean shouldAttemptTierB(String className) {
        if (!shouldInstrumentClass(className)) {
            return false;
        }
        boolean applicationClass = isApplicationClass(className);
        switch (mode) {
            case SAFE:
                return applicationClass;
            case BALANCED:
                return applicationClass || isDependencyCandidate(className);
            case AGGRESSIVE:
                return true;
            default:
                return applicationClass;
        }
    }

    private boolean isDependencyCandidate(String className) {
        return !isAlwaysExcluded(className) && !className.startsWith("javax/");
    }
}
