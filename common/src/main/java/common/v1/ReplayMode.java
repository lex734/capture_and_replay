package common.v1;

public enum ReplayMode {
    SAFE,
    BALANCED,
    AGGRESSIVE;

    public static ReplayMode fromString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return BALANCED;
        }
        switch (raw.trim().toLowerCase()) {
            case "safe":
                return SAFE;
            case "balanced":
                return BALANCED;
            case "aggressive":
                return AGGRESSIVE;
            default:
                throw new IllegalArgumentException("Unknown replay mode: " + raw);
        }
    }
}
