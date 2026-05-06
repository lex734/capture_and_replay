package common.v1;

import java.util.Objects;

public final class SemanticFieldEvent {
    private final long seq;
    private final long roleId;
    private final int packedType;
    private final int ownerSite;
    private final int ownerCount;
    private final FieldKey fieldKey;

    public SemanticFieldEvent(long seq, long roleId, int packedType, int ownerSite, int ownerCount, FieldKey fieldKey) {
        this.seq = seq;
        this.roleId = roleId;
        this.packedType = packedType;
        this.ownerSite = ownerSite;
        this.ownerCount = ownerCount;
        this.fieldKey = Objects.requireNonNull(fieldKey, "fieldKey");
    }

    public long seq() {
        return seq;
    }

    public long roleId() {
        return roleId;
    }

    public int packedType() {
        return packedType;
    }

    public int ownerSite() {
        return ownerSite;
    }

    public int ownerCount() {
        return ownerCount;
    }

    public FieldKey fieldKey() {
        return fieldKey;
    }
}
