package common;

import java.util.Objects;

public final class FieldInteractionDomain {
    private final String domainId;
    private final int ownerSite;
    private final int ownerCount;
    private final FieldKey fieldKey;

    public FieldInteractionDomain(String domainId, int ownerSite, int ownerCount, FieldKey fieldKey) {
        this.domainId = Objects.requireNonNull(domainId, "domainId");
        this.ownerSite = ownerSite;
        this.ownerCount = ownerCount;
        this.fieldKey = Objects.requireNonNull(fieldKey, "fieldKey");
    }

    public String domainId() {
        return domainId;
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
