package common;

import java.util.Objects;

public final class ReplayConstraint {
    private final ReducedConstraintKind kind;
    private final long predecessorSeq;
    private final long successorSeq;
    private final String domainId;

    public ReplayConstraint(ReducedConstraintKind kind, long predecessorSeq, long successorSeq, String domainId) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.predecessorSeq = predecessorSeq;
        this.successorSeq = successorSeq;
        this.domainId = domainId == null ? "" : domainId;
    }

    public ReducedConstraintKind kind() {
        return kind;
    }

    public long predecessorSeq() {
        return predecessorSeq;
    }

    public long successorSeq() {
        return successorSeq;
    }

    public String domainId() {
        return domainId;
    }
}
