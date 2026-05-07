package common.v1;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SemanticTraceRegistry {
    private static final List<SemanticObjectEvent> capturedObjectEvents =
            Collections.synchronizedList(new ArrayList<>());
    private static final Map<Long, SemanticObjectEvent> replayObjectEventsBySeq = new ConcurrentHashMap<>();
    private static final Map<Long, SemanticFieldEvent> replayFieldEventsBySeq = new ConcurrentHashMap<>();

    private SemanticTraceRegistry() {
    }

    public static void reset() {
        resetCaptureState();
        resetReplayState();
    }

    public static void resetCaptureState() {
        capturedObjectEvents.clear();
    }

    public static void resetReplayState() {
        replayObjectEventsBySeq.clear();
        replayFieldEventsBySeq.clear();
    }

    public static void recordFieldEvent(long seq, long roleId, int packedType, int ownerSite, int ownerCount, FieldKey fieldKey) {
        if (fieldKey == null) {
            return;
        }
        SemanticFieldEvent event = SemanticFieldEvent.capture(
                seq, roleId, packedType, ownerSite, ownerCount, fieldKey);
        capturedObjectEvents.add(event.event());
    }

    public static void recordArrayEvent(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int index) {
        SemanticObjectEvent event = SemanticObjectEvent.array(
                seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, index);
        capturedObjectEvents.add(event);
    }

    public static void recordAtomicEvent(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int index) {
        SemanticObjectEvent event = SemanticObjectEvent.atomic(
                seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, index);
        capturedObjectEvents.add(event);
    }

    public static void recordSyncEvent(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int sourceSiteId) {
        capturedObjectEvents.add(SemanticObjectEvent.sync(
                seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, sourceSiteId));
    }

    public static void recordThreadEvent(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int sourceSiteId, int targetRoleId) {
        capturedObjectEvents.add(SemanticObjectEvent.thread(
                seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, sourceSiteId, targetRoleId));
    }

    public static void recordClassInitEvent(long seq, long roleId, int packedType, int sourceSiteId) {
        capturedObjectEvents.add(SemanticObjectEvent.classInit(seq, roleId, packedType, sourceSiteId));
    }

    public static void recordExceptionEvent(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int sourceSiteId) {
        capturedObjectEvents.add(SemanticObjectEvent.exception(
                seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, sourceSiteId));
    }

    public static void recordNondeterministicEvent(long seq, long roleId, int packedType, int sourceSiteId) {
        capturedObjectEvents.add(SemanticObjectEvent.nondeterministic(seq, roleId, packedType, sourceSiteId));
    }

    public static void recordReplayObjectEvent(long replaySeq, SemanticObjectEvent event) {
        if (event == null) {
            return;
        }
        replayObjectEventsBySeq.put(replaySeq, event);
        if (event.isField() && event.fieldKey() != null) {
            replayFieldEventsBySeq.put(replaySeq,
                    SemanticFieldEvent.capture(event.seq(), event.roleId(), event.packedType(),
                            event.ownerSite(), event.ownerCount(), event.fieldKey()));
        }
    }

    public static SemanticFieldEvent lookupFieldEvent(long replaySeq) {
        return replayFieldEventsBySeq.get(replaySeq);
    }

    public static SemanticObjectEvent lookupObjectEvent(long replaySeq) {
        return replayObjectEventsBySeq.get(replaySeq);
    }

    public static Collection<SemanticObjectEvent> snapshotCapturedObjectEvents() {
        synchronized (capturedObjectEvents) {
            return new ArrayList<>(capturedObjectEvents);
        }
    }

    public static String semanticShapeKey(long replaySeq, String domainId) {
        SemanticObjectEvent event = lookupObjectEvent(replaySeq);
        return event == null ? "" : event.semanticObjectShape(domainId);
    }
}
