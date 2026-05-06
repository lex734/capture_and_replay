package common.v1;

import java.util.Objects;

public final class ReplayResult {
    private final OutcomeRelation outcomeRelation;
    private final ReplayQuality replayQuality;
    private final String details;

    public ReplayResult(OutcomeRelation outcomeRelation, ReplayQuality replayQuality, String details) {
        this.outcomeRelation = Objects.requireNonNull(outcomeRelation, "outcomeRelation");
        this.replayQuality = Objects.requireNonNull(replayQuality, "replayQuality");
        this.details = details == null ? "" : details;
    }

    public OutcomeRelation outcomeRelation() {
        return outcomeRelation;
    }

    public ReplayQuality replayQuality() {
        return replayQuality;
    }

    public String details() {
        return details;
    }
}
