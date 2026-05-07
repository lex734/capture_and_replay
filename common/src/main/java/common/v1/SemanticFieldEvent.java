package common.v1;

import java.util.Objects;

public final class SemanticFieldEvent {
    private final SemanticObjectEvent event;

    private SemanticFieldEvent(SemanticObjectEvent event) {
        this.event = Objects.requireNonNull(event, "event");
    }

    public static SemanticFieldEvent capture(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, FieldKey fieldKey) {
        return new SemanticFieldEvent(SemanticObjectEvent.field(
                seq, roleId, packedType, ownerSite, ownerCount, fieldKey));
    }

    public SemanticObjectEvent event() {
        return event;
    }

    public long seq() {
        return event.seq();
    }

    public long roleId() {
        return event.roleId();
    }

    public int packedType() {
        return event.packedType();
    }

    public SemanticIdentity.TraceObjectRef ownerRef() {
        return event.ownerRef();
    }

    public int ownerSite() {
        return event.ownerSite();
    }

    public int ownerCount() {
        return event.ownerCount();
    }

    public FieldKey fieldKey() {
        return event.fieldKey();
    }

    public int baseType() {
        return event.baseType();
    }

    public boolean isVolatile() {
        return event.isVolatile();
    }

    public boolean isStatic() {
        return event.isStatic();
    }

    public String semanticObjectShape(String domainId) {
        return event.semanticObjectShape(domainId);
    }
}
