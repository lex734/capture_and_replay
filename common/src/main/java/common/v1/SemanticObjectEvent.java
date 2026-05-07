package common.v1;

import common.BinarySchema;
import java.util.Objects;

public final class SemanticObjectEvent {
    public enum Kind {
        FIELD,
        ARRAY,
        ATOMIC,
        SYNC,
        THREAD,
        CLASS_INIT,
        EXCEPTION,
        NONDET
    }

    private final long seq;
    private final long roleId;
    private final int packedType;
    private final Kind kind;
    private final SemanticIdentity.TraceObjectRef ownerRef;
    private final String ownerTypeName;
    private final FieldKey fieldKey;
    private final int index;
    private final int sourceSiteId;
    private final int targetRoleId;

    private SemanticObjectEvent(long seq, long roleId, int packedType, Kind kind,
            SemanticIdentity.TraceObjectRef ownerRef, String ownerTypeName, FieldKey fieldKey, int index,
            int sourceSiteId, int targetRoleId) {
        this.seq = seq;
        this.roleId = roleId;
        this.packedType = packedType;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.ownerRef = Objects.requireNonNull(ownerRef, "ownerRef");
        this.ownerTypeName = ownerTypeName == null ? "" : ownerTypeName;
        this.fieldKey = fieldKey;
        this.index = index;
        this.sourceSiteId = sourceSiteId;
        this.targetRoleId = targetRoleId;
    }

    public static SemanticObjectEvent field(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, FieldKey fieldKey) {
        String ownerType = fieldKey == null ? "" : fieldKey.owner().internalName();
        return new SemanticObjectEvent(seq, roleId, packedType, Kind.FIELD,
                SemanticIdentity.objectRef(ownerSite, ownerCount), ownerType, fieldKey, -1, -1, -1);
    }

    public static SemanticObjectEvent array(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int index) {
        return new SemanticObjectEvent(seq, roleId, packedType, Kind.ARRAY,
                SemanticIdentity.objectRef(ownerSite, ownerCount), ownerTypeName, null, index, -1, -1);
    }

    public static SemanticObjectEvent atomic(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int index) {
        return new SemanticObjectEvent(seq, roleId, packedType, Kind.ATOMIC,
                SemanticIdentity.objectRef(ownerSite, ownerCount), ownerTypeName, null, index, -1, -1);
    }

    public static SemanticObjectEvent sync(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int sourceSiteId) {
        return new SemanticObjectEvent(seq, roleId, packedType, Kind.SYNC,
                SemanticIdentity.objectRef(ownerSite, ownerCount), ownerTypeName, null, -1, sourceSiteId, -1);
    }

    public static SemanticObjectEvent thread(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int sourceSiteId, int targetRoleId) {
        return new SemanticObjectEvent(seq, roleId, packedType, Kind.THREAD,
                SemanticIdentity.objectRef(ownerSite, ownerCount), ownerTypeName, null, -1, sourceSiteId, targetRoleId);
    }

    public static SemanticObjectEvent classInit(long seq, long roleId, int packedType, int sourceSiteId) {
        return new SemanticObjectEvent(seq, roleId, packedType, Kind.CLASS_INIT,
                SemanticIdentity.objectRef(0, 0), "", null, -1, sourceSiteId, -1);
    }

    public static SemanticObjectEvent exception(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int sourceSiteId) {
        return new SemanticObjectEvent(seq, roleId, packedType, Kind.EXCEPTION,
                SemanticIdentity.objectRef(ownerSite, ownerCount), ownerTypeName, null, -1, sourceSiteId, -1);
    }

    public static SemanticObjectEvent nondeterministic(long seq, long roleId, int packedType, int sourceSiteId) {
        return new SemanticObjectEvent(seq, roleId, packedType, Kind.NONDET,
                SemanticIdentity.objectRef(0, 0), "", null, -1, sourceSiteId, -1);
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

    public Kind kind() {
        return kind;
    }

    public SemanticIdentity.TraceObjectRef ownerRef() {
        return ownerRef;
    }

    public int ownerSite() {
        return ownerRef.traceSite();
    }

    public int ownerCount() {
        return ownerRef.traceCount();
    }

    public String ownerTypeName() {
        return ownerTypeName;
    }

    public FieldKey fieldKey() {
        return fieldKey;
    }

    public int index() {
        return index;
    }

    public int sourceSiteId() {
        return sourceSiteId;
    }

    public int targetRoleId() {
        return targetRoleId;
    }

    public int baseType() {
        return packedType & 0xFF;
    }

    public int flags() {
        return (packedType >>> 8) & 0xFF;
    }

    public boolean isField() {
        return kind == Kind.FIELD;
    }

    public boolean isArray() {
        return kind == Kind.ARRAY;
    }

    public boolean isAtomic() {
        return kind == Kind.ATOMIC;
    }

    public boolean isSync() {
        return kind == Kind.SYNC;
    }

    public boolean isThread() {
        return kind == Kind.THREAD;
    }

    public boolean isClassInit() {
        return kind == Kind.CLASS_INIT;
    }

    public boolean isException() {
        return kind == Kind.EXCEPTION;
    }

    public boolean isNondeterministic() {
        return kind == Kind.NONDET;
    }

    public boolean isVolatile() {
        return (flags() & BinarySchema.Flags.IS_VOLATILE) != 0;
    }

    public boolean isStatic() {
        return (flags() & BinarySchema.Flags.IS_STATIC) != 0;
    }

    public boolean isObjectValued() {
        return (flags() & BinarySchema.Flags.IS_OBJECT_VALUE) != 0;
    }

    public boolean isArrayAtomic() {
        return (flags() & BinarySchema.Flags.IS_ARRAY_ATOMIC) != 0;
    }

    public boolean isArrayValued() {
        return (flags() & BinarySchema.Flags.IS_ARRAY_VALUED) != 0;
    }

    public String domainId() {
        switch (kind) {
            case FIELD:
                if (fieldKey == null) {
                    return "field-unknown:" + ownerSite() + ":" + ownerCount();
                }
                return "field:" + ownerSite() + ":" + ownerCount() + ":" + fieldKey.owner().internalName()
                        + "." + fieldKey.name() + ":" + fieldKey.descriptor();
            case ARRAY:
                return "array:" + ownerSite() + ":" + ownerCount() + ":" + index;
            case ATOMIC:
                if (index >= 0) {
                    return "atomic-array:" + ownerSite() + ":" + ownerCount() + ":" + index;
                }
                return "atomic-object:" + ownerSite() + ":" + ownerCount();
            case SYNC:
                if (ownerSite() != 0 || ownerCount() != 0) {
                    return "sync-object:" + ownerSite() + ":" + ownerCount();
                }
                return "sync-site:" + baseType() + ":" + sourceSiteId;
            case THREAD:
                if (ownerSite() != 0 || ownerCount() != 0) {
                    return "thread-object:" + ownerSite() + ":" + ownerCount();
                }
                return "thread-role:" + (targetRoleId >= 0 ? targetRoleId : roleId);
            case CLASS_INIT:
                return "class-init:" + sourceSiteId;
            case EXCEPTION:
                return "exception:" + ownerSite() + ":" + ownerCount() + ":" + sourceSiteId;
            case NONDET:
                return "nondet:" + baseType() + ":" + sourceSiteId;
            default:
                return "";
        }
    }

    public String semanticObjectShape(String domainId) {
        return SemanticIdentity.semanticShapeKey(baseType(), domainId, fieldKey, ownerTypeName);
    }
}
