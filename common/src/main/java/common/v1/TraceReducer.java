package common.v1;

import common.BinarySchema;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TraceReducer {
    private static final String DEFAULT_TRACE_FILE = "trace.bin";
    private static final String DEFAULT_SEMANTIC_CAPTURE_FILE = "trace-semantic.tsv";

    private TraceReducer() {
    }

    public static void main(String[] args) throws IOException {
        String output   = args.length > 0 ? args[0] : "trace-reduced.tsv";
        String trace    = args.length > 1 ? args[1] : DEFAULT_TRACE_FILE;
        String semantic = args.length > 2 ? args[2] : DEFAULT_SEMANTIC_CAPTURE_FILE;
        if (semantic != null && !semantic.isEmpty() && !new java.io.File(semantic).exists()) {
            semantic = null;
        }
        reduceFieldInteractionsToFile(output, trace, semantic);
    }

    public static void reduceFieldInteractionsToFile(String fileName) throws IOException {
        reduceFieldInteractionsToFile(fileName, DEFAULT_TRACE_FILE, DEFAULT_SEMANTIC_CAPTURE_FILE);
    }

    public static void reduceFieldInteractionsToFile(String fileName, String traceFileName, String semanticFileName)
            throws IOException {
        ReducedTraceRegistry.reset();
        SemanticTraceRegistry.resetReplayState();

        List<RawEvent> rawEvents = readRawEvents(traceFileName);
        Map<CaptureSemanticKey, ArrayDeque<SemanticObjectEvent>> semanticEvents = new HashMap<>();
        Collection<SemanticObjectEvent> objectEvents = SemanticTraceRegistry.snapshotCapturedObjectEvents();
        if (objectEvents.isEmpty() && semanticFileName != null && !semanticFileName.isEmpty()) {
            objectEvents = SemanticTraceRegistry.loadCaptured(semanticFileName);
        }
        for (SemanticObjectEvent objectEvent : objectEvents) {
            semanticEvents.computeIfAbsent(CaptureSemanticKey.of(objectEvent), ignored -> new ArrayDeque<>())
                    .add(objectEvent);
        }

        List<RawEvent> events = new ArrayList<>();

        for (RawEvent event : rawEvents) {
            if (!isReplayRole(event.roleId)) {
                continue;
            }
            if (!isSupportedReplayEvent(event)) {
                continue;
            }
            SemanticObjectEvent semanticEvent = pollSemanticEvent(semanticEvents, event);
            event.domainId = resolveDomainId(event, semanticEvent);
            event.semanticEvent = semanticEvent;
            events.add(event);
        }

        long concurrentWindowStartSeq = firstConcurrentWindowStartSeq(events);
        List<RawEvent> concurrentEvents = filterConcurrentWindow(events, concurrentWindowStartSeq);
        Map<String, Set<Long>> rolesByDomain = new HashMap<>();
        Map<String, List<RawEvent>> eventsByDomain = new HashMap<>();
        Map<Long, List<RawEvent>> publisherEventsByValueObject = new HashMap<>();
        Map<Long, List<RawEvent>> threadLifecycleEventsByRole = new HashMap<>();
        Map<Long, RawEvent> threadStartByChildRole = new HashMap<>();
        Map<Long, RawEvent> epochReleaseByEpoch = new HashMap<>();

        for (RawEvent event : concurrentEvents) {
            if (event.domainId != null && !event.domainId.isEmpty()) {
                rolesByDomain.computeIfAbsent(event.domainId, ignored -> new HashSet<>()).add(event.roleId);
                eventsByDomain.computeIfAbsent(event.domainId, ignored -> new ArrayList<>()).add(event);
            }
            if (event.baseType == BinarySchema.Event.THREAD_START && event.data1 > 0) {
                threadStartByChildRole.put((long) event.data1, event);
            }
            if (event.isThreadLifecycleEvent()) {
                addThreadLifecycleLink(threadLifecycleEventsByRole, event.roleId, event);
                if (event.semanticEvent != null && event.semanticEvent.targetRoleId() > 0) {
                    addThreadLifecycleLink(threadLifecycleEventsByRole, event.semanticEvent.targetRoleId(), event);
                } else if (event.baseType == BinarySchema.Event.THREAD_START && event.data1 > 0) {
                    addThreadLifecycleLink(threadLifecycleEventsByRole, (long) event.data1, event);
                }
            }
            long publishedValueKey = publishedValueTraceKey(event);
            if (publishedValueKey != Long.MIN_VALUE) {
                publisherEventsByValueObject.computeIfAbsent(publishedValueKey, ignored -> new ArrayList<>()).add(event);
            }
            if (event.isEpochRelease()) {
                epochReleaseByEpoch.putIfAbsent(event.epoch(), event);
            }
        }

        Map<Long, RawEvent> firstByRole = new HashMap<>();
        Map<Long, RawEvent> lastByRole = new HashMap<>();
        Map<String, RawEvent> lastByDomain = new HashMap<>();
        Map<RawEvent, Long> replayIdByEvent = new HashMap<>();
        Set<RawEvent> relevantEvents = computeRelevantEventClosure(
                concurrentEvents,
                rolesByDomain,
                eventsByDomain,
                publisherEventsByValueObject,
                threadLifecycleEventsByRole,
                threadStartByChildRole,
                epochReleaseByEpoch);
        Set<String> concurrentOwnerKeys = computeConcurrentOwnerKeys(concurrentEvents);
        Set<RawEvent> retainedEvents = new HashSet<>(relevantEvents);
        for (RawEvent event : concurrentEvents) {
            if (isSynchronizationProtocolEvent(event.baseType)) {
                retainedEvents.add(event);
                continue;
            }
            if (shouldRetainConcurrentOwnerEvent(event, concurrentOwnerKeys, concurrentWindowStartSeq)) {
                retainedEvents.add(event);
            }
        }
        retainedEvents.removeIf(event -> !shouldRetainRelevantEvent(event, rolesByDomain, concurrentOwnerKeys,
                concurrentWindowStartSeq));
        long nextReplayEventId = 1L;

        for (RawEvent event : concurrentEvents) {
            if (!retainedEvents.contains(event)) {
                continue;
            }

            long replayEventId = nextReplaySeq(nextReplayEventId++, event.seq());
            replayIdByEvent.put(event, replayEventId);

            ReducedTraceRegistry.recordReducedEvent(replayEventId, event.toArray());
            ReducedTraceRegistry.recordEventDomain(replayEventId, event.domainId, true);
            ReducedTraceRegistry.recordEventEpoch(replayEventId, event.epoch());
            if (event.semanticEvent != null) {
                SemanticTraceRegistry.recordReplayObjectEvent(replayEventId, rekeySemanticEvent(replayEventId, event.semanticEvent));
            }

            if (event.semanticEvent != null && event.semanticEvent.isField()) {
                FieldInteractionDomain fieldDomain = new FieldInteractionDomain(
                        event.domainId,
                        event.semanticEvent.ownerSite(),
                        event.semanticEvent.ownerCount(),
                        event.semanticEvent.fieldKey());
                ReducedTraceRegistry.recordFieldEvent(replayEventId, fieldDomain, true);
            }

            RawEvent previousRoleEvent = lastByRole.put(event.roleId, event);
            if (previousRoleEvent != null) {
                ReducedTraceRegistry.recordConstraint(new ReplayConstraint(
                        ReducedConstraintKind.THREAD_ORDER,
                        replayIdByEvent.get(previousRoleEvent),
                        replayEventId,
                        event.domainId));
            } else {
                firstByRole.put(event.roleId, event);
            }

            if (event.domainId != null && !event.domainId.isEmpty()) {
                RawEvent previousDomainEvent = lastByDomain.put(event.domainId, event);
                if (previousDomainEvent != null && shouldRecordDomainOrder(previousDomainEvent, event)) {
                    ReducedTraceRegistry.recordConstraint(new ReplayConstraint(
                            ReducedConstraintKind.DOMAIN_ORDER,
                            replayIdByEvent.get(previousDomainEvent),
                            replayEventId,
                            event.domainId));
                }
            }

            RawEvent epochRelease = epochReleaseByEpoch.get(event.epoch());
            if (epochRelease != null && epochRelease != event && relevantEvents.contains(epochRelease)) {
                Long predecessorReplayId = replayIdByEvent.get(epochRelease);
                if (predecessorReplayId != null) {
                    ReducedTraceRegistry.recordConstraint(new ReplayConstraint(
                            ReducedConstraintKind.JMM_SYNCHRONIZES_WITH,
                            predecessorReplayId,
                            replayEventId,
                            event.domainId == null ? "" : event.domainId));
                }
            }
        }

        for (RawEvent event : concurrentEvents) {
            if (event.baseType != BinarySchema.Event.THREAD_START || !retainedEvents.contains(event)) {
                continue;
            }
            long childRole = event.data1;
            RawEvent childFirst = firstByRole.get(childRole);
            if (childFirst != null) {
                ReducedTraceRegistry.recordConstraint(new ReplayConstraint(
                        ReducedConstraintKind.THREAD_START_CAUSAL,
                        replayIdByEvent.get(event),
                        replayIdByEvent.get(childFirst),
                        event.domainId == null ? "" : event.domainId));
            }
        }

        ReducedTraceRegistry.save(fileName);
    }

    private static Set<String> computeConcurrentOwnerKeys(List<RawEvent> events) {
        Map<String, Set<Long>> rolesByOwner = new HashMap<>();
        Set<String> concurrentOwnerKeys = new HashSet<>();
        for (RawEvent event : events) {
            if (event == null || !event.isOwnerScopedRuntimeEvent() || !isReplayRole(event.roleId)) {
                continue;
            }
            String ownerKey = concurrentRetentionKey(event);
            if (ownerKey == null || ownerKey.isEmpty()) {
                continue;
            }
            Set<Long> roles = rolesByOwner.computeIfAbsent(ownerKey, ignored -> new HashSet<>());
            roles.add(event.roleId);
            if (roles.size() > 1) {
                concurrentOwnerKeys.add(ownerKey);
            }
        }
        return concurrentOwnerKeys;
    }

    private static boolean shouldRetainConcurrentOwnerEvent(RawEvent event, Set<String> concurrentOwnerKeys,
            long concurrentWindowStartSeq) {
        if (event == null || !event.isOwnerScopedRuntimeEvent()) {
            return false;
        }
        if (concurrentWindowStartSeq != Long.MAX_VALUE && event.seq() < concurrentWindowStartSeq) {
            return false;
        }
        String ownerKey = concurrentRetentionKey(event);
        return ownerKey != null && !ownerKey.isEmpty() && concurrentOwnerKeys.contains(ownerKey);
    }

    private static boolean shouldRetainRelevantEvent(RawEvent event, Map<String, Set<Long>> rolesByDomain,
            Set<String> concurrentOwnerKeys, long concurrentWindowStartSeq) {
        if (event == null) {
            return false;
        }
        if (concurrentWindowStartSeq != Long.MAX_VALUE && event.seq() < concurrentWindowStartSeq) {
            return false;
        }
        switch (event.baseType) {
            case BinarySchema.Event.MONITOR_ENTER:
            case BinarySchema.Event.MONITOR_EXIT:
            case BinarySchema.Event.THREAD_WAIT:
            case BinarySchema.Event.THREAD_NOTIFY:
            case BinarySchema.Event.THREAD_NOTIFY_ALL:
                return true;
            default:
                break;
        }
        String ownerKey = concurrentRetentionKey(event);
        if (isStaticObjectFieldEvent(event)) {
            return ownerKey != null && !ownerKey.isEmpty() && concurrentOwnerKeys.contains(ownerKey);
        }
        if (ownerKey != null && !ownerKey.isEmpty() && event.isOwnerScopedRuntimeEvent()) {
            return concurrentOwnerKeys.contains(ownerKey);
        }
        if (ownerKey != null && !ownerKey.isEmpty() && concurrentOwnerKeys.contains(ownerKey)) {
            return true;
        }
        if (event.domainId == null || event.domainId.isEmpty()) {
            return true;
        }
        Set<Long> roles = rolesByDomain.get(event.domainId);
        if (roles != null && roles.size() > 1) {
            return true;
        }
        switch (event.baseType) {
            case BinarySchema.Event.THREAD_START:
            case BinarySchema.Event.THREAD_JOIN:
            case BinarySchema.Event.THREAD_JOIN_TIMEOUT:
            case BinarySchema.Event.CLASS_INIT_BEGIN:
            case BinarySchema.Event.CLASS_INIT_END:
                return true;
            default:
                break;
        }
        return false;
    }

    private static boolean isStaticObjectFieldEvent(RawEvent event) {
        return event != null
                && event.semanticEvent != null
                && event.semanticEvent.isField()
                && event.semanticEvent.isStatic()
                && event.semanticEvent.isObjectValued();
    }

    private static String concurrentRetentionKey(RawEvent event) {
        if (event == null || !event.isOwnerScopedRuntimeEvent()) {
            return null;
        }
        if (event.semanticEvent != null && event.semanticEvent.isField() && event.semanticEvent.isStatic()) {
            return event.domainId == null || event.domainId.isEmpty() ? null : "static-field:" + event.domainId;
        }
        long ownerKey = ownerTraceKey(event);
        return ownerKey == Long.MIN_VALUE ? null : "owner:" + ownerKey;
    }

    private static long firstConcurrentWindowStartSeq(List<RawEvent> events) {
        for (RawEvent event : events) {
            if (event != null
                    && event.baseType == BinarySchema.Event.THREAD_START
                    && event.data1 > 0) {
                return event.seq();
            }
        }
        return Long.MIN_VALUE;
    }

    private static List<RawEvent> filterConcurrentWindow(List<RawEvent> events, long concurrentWindowStartSeq) {
        if (concurrentWindowStartSeq == Long.MIN_VALUE) {
            return new ArrayList<>(events);
        }
        List<RawEvent> concurrentEvents = new ArrayList<>();
        for (RawEvent event : events) {
            if (event != null && event.seq() >= concurrentWindowStartSeq) {
                concurrentEvents.add(event);
            }
        }
        return concurrentEvents;
    }

    private static SemanticObjectEvent pollSemanticEvent(Map<CaptureSemanticKey, ArrayDeque<SemanticObjectEvent>> semanticEvents,
            RawEvent event) {
        ArrayDeque<SemanticObjectEvent> queue = semanticEvents.get(CaptureSemanticKey.of(event));
        return queue == null ? null : queue.pollFirst();
    }

    private static long nextReplaySeq(long replayOrdinal, long capturedSeq) {
        long localSeq = capturedSeq & 0xFFFFFFFFL;
        return (replayOrdinal << 32) | localSeq;
    }

    private static SemanticObjectEvent rekeySemanticEvent(long replayEventId, SemanticObjectEvent event) {
        if (event.isField()) {
            return SemanticObjectEvent.field(replayEventId, event.roleId(), event.packedType(),
                    event.ownerSite(), event.ownerCount(), event.fieldKey());
        }
        if (event.isArray()) {
            return SemanticObjectEvent.array(replayEventId, event.roleId(), event.packedType(),
                    event.ownerSite(), event.ownerCount(), event.ownerTypeName(), event.index());
        }
        if (event.isAtomic()) {
            return SemanticObjectEvent.atomic(replayEventId, event.roleId(), event.packedType(),
                    event.ownerSite(), event.ownerCount(), event.ownerTypeName(), event.index());
        }
        if (event.isThread()) {
            return SemanticObjectEvent.thread(replayEventId, event.roleId(), event.packedType(),
                    event.ownerSite(), event.ownerCount(), event.ownerTypeName(), event.sourceSiteId(),
                    event.targetRoleId());
        }
        if (event.isClassInit()) {
            return SemanticObjectEvent.classInit(replayEventId, event.roleId(), event.packedType(),
                    event.sourceSiteId());
        }
        if (event.isException()) {
            return SemanticObjectEvent.exception(replayEventId, event.roleId(), event.packedType(),
                    event.ownerSite(), event.ownerCount(), event.ownerTypeName(), event.sourceSiteId());
        }
        if (event.isNondeterministic()) {
            return SemanticObjectEvent.nondeterministic(replayEventId, event.roleId(), event.packedType(),
                    event.sourceSiteId());
        }
        return SemanticObjectEvent.sync(replayEventId, event.roleId(), event.packedType(),
                event.ownerSite(), event.ownerCount(), event.ownerTypeName(), event.sourceSiteId());
    }

    private static Set<RawEvent> computeRelevantEventClosure(
            List<RawEvent> events,
            Map<String, Set<Long>> rolesByDomain,
            Map<String, List<RawEvent>> eventsByDomain,
            Map<Long, List<RawEvent>> publisherEventsByValueObject,
            Map<Long, List<RawEvent>> threadLifecycleEventsByRole,
            Map<Long, RawEvent> threadStartByChildRole,
            Map<Long, RawEvent> epochReleaseByEpoch) {
        Set<RawEvent> relevantEvents = new HashSet<>();
        ArrayDeque<RawEvent> work = new ArrayDeque<>();

        for (Map.Entry<String, Set<Long>> entry : rolesByDomain.entrySet()) {
            if (entry.getValue().size() > 1) {
                enqueueAll(work, eventsByDomain.get(entry.getKey()));
            }
        }
        for (RawEvent event : events) {
            if (event.semanticEvent != null && isVolatileEvent(event.semanticEvent)) {
                work.add(event);
            }
        }

        while (!work.isEmpty()) {
            RawEvent event = work.pollFirst();
            if (event == null || !relevantEvents.add(event)) {
                continue;
            }

            enqueueAll(work, eventsByDomain.get(event.domainId));

            RawEvent parentStart = threadStartByChildRole.get(event.roleId);
            if (parentStart != null) {
                work.add(parentStart);
            }

            long ownerKey = ownerTraceKey(event);
            if (ownerKey != Long.MIN_VALUE) {
                enqueueAll(work, publisherEventsByValueObject.get(ownerKey));
            }

            RawEvent epochRelease = epochReleaseByEpoch.get(event.epoch());
            if (epochRelease != null) {
                work.add(epochRelease);
            }
        }

        return relevantEvents;
    }

    private static boolean isSupportedReplayEvent(RawEvent event) {
        switch (event.baseType) {
            case BinarySchema.Event.MONITOR_ENTER:
            case BinarySchema.Event.MONITOR_EXIT:
            case BinarySchema.Event.THREAD_PARK:
            case BinarySchema.Event.THREAD_UNPARK:
            case BinarySchema.Event.FIELD_READ:
            case BinarySchema.Event.FIELD_WRITE:
            case BinarySchema.Event.ARRAY_READ:
            case BinarySchema.Event.ARRAY_WRITE:
            case BinarySchema.Event.THREAD_START:
            case BinarySchema.Event.THREAD_JOIN:
            case BinarySchema.Event.THREAD_INTERRUPT:
            case BinarySchema.Event.THREAD_SLEEP:
            case BinarySchema.Event.THREAD_WAKEUP:
            case BinarySchema.Event.THREAD_YIELD:
            case BinarySchema.Event.THREAD_WAIT:
            case BinarySchema.Event.THREAD_NOTIFY:
            case BinarySchema.Event.THREAD_NOTIFY_ALL:
            case BinarySchema.Event.THREAD_JOIN_TIMEOUT:
            case BinarySchema.Event.THREAD_INTERRUPT_CHECK:
            case BinarySchema.Event.ATOMIC_READ:
            case BinarySchema.Event.ATOMIC_WRITE:
            case BinarySchema.Event.ATOMIC_RMW:
            case BinarySchema.Event.CLASS_INIT_BEGIN:
            case BinarySchema.Event.CLASS_INIT_END:
            case BinarySchema.Event.EXCEPTION_THROW:
            case BinarySchema.Event.ATOMIC_CAS:
            case BinarySchema.Event.NONDETERMINISTIC_INT:
            case BinarySchema.Event.NONDETERMINISTIC_LONG:
                return true;
            default:
                return false;
        }
    }

    private static boolean isVolatileEvent(SemanticObjectEvent event) {
        return event != null && event.isVolatile();
    }

    private static boolean shouldRecordDomainOrder(RawEvent previousDomainEvent, RawEvent event) {
        if (previousDomainEvent == null || event == null) {
            return false;
        }
        // Synchronization protocol events are already anchored by per-thread order and
        // JMM release edges. Adding raw post-observation domain order between them can
        // manufacture impossible requirements such as a later acquire needing to
        // precede the exiting thread's post-release MONITOR_EXIT log on the same
        // monitor.
        return !(isSynchronizationProtocolEvent(previousDomainEvent.baseType)
                && isSynchronizationProtocolEvent(event.baseType));
    }

    private static boolean isSynchronizationProtocolEvent(int baseType) {
        switch (baseType) {
            case BinarySchema.Event.MONITOR_ENTER:
            case BinarySchema.Event.MONITOR_EXIT:
            case BinarySchema.Event.THREAD_WAIT:
            case BinarySchema.Event.THREAD_NOTIFY:
            case BinarySchema.Event.THREAD_NOTIFY_ALL:
                return true;
            default:
                return false;
        }
    }

    private static boolean isReplayRole(long roleId) {
        return roleId > 0;
    }

    private static void enqueueAll(ArrayDeque<RawEvent> work, List<RawEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        work.addAll(events);
    }

    private static void addThreadLifecycleLink(Map<Long, List<RawEvent>> eventsByRole, long roleId, RawEvent event) {
        if (roleId <= 0) {
            return;
        }
        eventsByRole.computeIfAbsent(roleId, ignored -> new ArrayList<>()).add(event);
    }

    private static long ownerTraceKey(RawEvent event) {
        if (event.semanticEvent != null) {
            int site = event.semanticEvent.ownerSite();
            int count = event.semanticEvent.ownerCount();
            if (site != 0 || count != 0) {
                return packTraceKey(site, count);
            }
        }
        if (event.isArrayScopedLike()) {
            int site = event.ownerSiteFromPacked();
            int count = event.objSite;
            return (site == 0 && count == 0) ? Long.MIN_VALUE : packTraceKey(site, count);
        }
        return (event.objSite == 0 && event.objCount == 0) ? Long.MIN_VALUE : packTraceKey(event.objSite, event.objCount);
    }

    private static long publishedValueTraceKey(RawEvent event) {
        if ((event.flags & BinarySchema.Flags.IS_OBJECT_VALUE) == 0) {
            return Long.MIN_VALUE;
        }
        switch (event.baseType) {
            case BinarySchema.Event.FIELD_WRITE:
            case BinarySchema.Event.ARRAY_WRITE:
            case BinarySchema.Event.ATOMIC_WRITE:
            case BinarySchema.Event.ATOMIC_RMW:
            case BinarySchema.Event.ATOMIC_CAS:
                if (event.data1 == 0 && event.data2 == 0) {
                    return Long.MIN_VALUE;
                }
                return packTraceKey(event.data1, event.data2);
            default:
                return Long.MIN_VALUE;
        }
    }

    private static long packTraceKey(int siteId, int count) {
        return ((long) siteId << 32) | (count & 0xFFFFFFFFL);
    }

    private static String resolveDomainId(RawEvent event, SemanticObjectEvent semanticEvent) {
        if (semanticEvent != null) {
            String domainId = semanticEvent.domainId();
            if (domainId != null && !domainId.isEmpty()) {
                return domainId;
            }
        }
        switch (event.baseType) {
            case BinarySchema.Event.FIELD_READ:
            case BinarySchema.Event.FIELD_WRITE:
                return "field-unknown:" + event.objSite + ":" + event.objCount;
            case BinarySchema.Event.ARRAY_READ:
            case BinarySchema.Event.ARRAY_WRITE:
                return "array:" + event.arrayOwnerSite() + ":" + event.arrayOwnerCount() + ":" + event.objCount;
            case BinarySchema.Event.MONITOR_ENTER:
            case BinarySchema.Event.MONITOR_EXIT:
            case BinarySchema.Event.THREAD_WAIT:
            case BinarySchema.Event.THREAD_NOTIFY:
            case BinarySchema.Event.THREAD_NOTIFY_ALL:
            case BinarySchema.Event.THREAD_PARK:
            case BinarySchema.Event.THREAD_UNPARK:
            case BinarySchema.Event.THREAD_INTERRUPT:
            case BinarySchema.Event.THREAD_INTERRUPT_CHECK:
            case BinarySchema.Event.EXCEPTION_THROW:
                return "object:" + event.objSite + ":" + event.objCount;
            case BinarySchema.Event.THREAD_START:
            case BinarySchema.Event.THREAD_JOIN:
            case BinarySchema.Event.THREAD_JOIN_TIMEOUT:
            case BinarySchema.Event.THREAD_WAKEUP:
                return "thread-object:" + event.objSite + ":" + event.objCount;
            case BinarySchema.Event.THREAD_SLEEP:
            case BinarySchema.Event.THREAD_YIELD:
                return "thread-role:" + event.roleId;
            case BinarySchema.Event.ATOMIC_READ:
            case BinarySchema.Event.ATOMIC_WRITE:
            case BinarySchema.Event.ATOMIC_RMW:
            case BinarySchema.Event.ATOMIC_CAS:
                if (event.isArrayAtomic()) {
                    return "atomic-array:" + event.atomicOwnerSite() + ":" + event.atomicOwnerCount() + ":" + event.objCount;
                }
                return "atomic-object:" + event.objSite + ":" + event.objCount;
            case BinarySchema.Event.CLASS_INIT_BEGIN:
            case BinarySchema.Event.CLASS_INIT_END:
                return "class-init:" + event.data2;
            case BinarySchema.Event.NONDETERMINISTIC_INT:
            case BinarySchema.Event.NONDETERMINISTIC_LONG:
                return "nondet:" + event.baseType + ":" + event.objCount;
            default:
                return "event:" + event.baseType + ":" + event.objSite + ":" + event.objCount;
        }
    }

    private static List<RawEvent> readRawEvents(String fileName) throws IOException {
        List<RawEvent> events = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(fileName, "r")) {
            long fileSize = raf.length();
            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            long maxSlots = fileSize / BinarySchema.RECORD_SIZE;
            for (long i = 0; i < maxSlots; i++) {
                int pos = (int) (i * BinarySchema.RECORD_SIZE);
                long seq = buffer.getLong(pos);
                if (seq == 0L) {
                    continue;
                }
                events.add(new RawEvent(
                        seq,
                        buffer.getLong(pos + 8),
                        buffer.getInt(pos + 16),
                        buffer.getInt(pos + 20),
                        buffer.getInt(pos + 24),
                        buffer.getInt(pos + 28),
                        buffer.getInt(pos + 32)));
            }
        }
        return events;
    }

    private static final class RawEvent {
        private final long seq;
        private final long roleId;
        private final int packedType;
        private final int objSite;
        private final int objCount;
        private final int data1;
        private final int data2;
        private final int baseType;
        private final int flags;
        private String domainId;
        private SemanticObjectEvent semanticEvent;

        private RawEvent(long seq, long roleId, int packedType, int objSite, int objCount, int data1, int data2) {
            this.seq = seq;
            this.roleId = roleId;
            this.packedType = packedType;
            this.objSite = objSite;
            this.objCount = objCount;
            this.data1 = data1;
            this.data2 = data2;
            this.baseType = packedType & 0xFF;
            this.flags = (packedType >>> 8) & 0xFF;
        }

        private boolean isArrayAtomic() {
            return (flags & BinarySchema.Flags.IS_ARRAY_ATOMIC) != 0;
        }

        private boolean isArrayValued() {
            return (flags & BinarySchema.Flags.IS_ARRAY_VALUED) != 0;
        }

        private int arrayOwnerSite() {
            return packedType >>> 16;
        }

        private int arrayOwnerCount() {
            return objSite;
        }

        private int atomicOwnerSite() {
            return packedType >>> 16;
        }

        private int atomicOwnerCount() {
            return objSite;
        }

        private boolean isArrayScopedLike() {
            return (flags & (BinarySchema.Flags.IS_ARRAY_ATOMIC | BinarySchema.Flags.IS_ARRAY_VALUED)) != 0;
        }

        private boolean isOwnerScopedRuntimeEvent() {
            switch (baseType) {
                case BinarySchema.Event.FIELD_READ:
                case BinarySchema.Event.FIELD_WRITE:
                case BinarySchema.Event.ARRAY_READ:
                case BinarySchema.Event.ARRAY_WRITE:
                case BinarySchema.Event.ATOMIC_READ:
                case BinarySchema.Event.ATOMIC_WRITE:
                case BinarySchema.Event.ATOMIC_RMW:
                case BinarySchema.Event.ATOMIC_CAS:
                case BinarySchema.Event.MONITOR_ENTER:
                case BinarySchema.Event.MONITOR_EXIT:
                case BinarySchema.Event.THREAD_WAIT:
                case BinarySchema.Event.THREAD_NOTIFY:
                case BinarySchema.Event.THREAD_NOTIFY_ALL:
                case BinarySchema.Event.THREAD_INTERRUPT:
                case BinarySchema.Event.THREAD_INTERRUPT_CHECK:
                    return true;
                default:
                    return false;
            }
        }

        private int ownerSiteFromPacked() {
            return packedType >>> 16;
        }

        private long epoch() {
            return seq >>> 32;
        }

        private boolean isThreadLifecycleEvent() {
            switch (baseType) {
                case BinarySchema.Event.THREAD_START:
                case BinarySchema.Event.THREAD_JOIN:
                case BinarySchema.Event.THREAD_JOIN_TIMEOUT:
                case BinarySchema.Event.THREAD_WAKEUP:
                case BinarySchema.Event.THREAD_INTERRUPT:
                case BinarySchema.Event.THREAD_INTERRUPT_CHECK:
                    return true;
                default:
                    return false;
            }
        }

        private boolean isEpochRelease() {
            switch (baseType) {
                case BinarySchema.Event.MONITOR_EXIT:
                case BinarySchema.Event.THREAD_START:
                case BinarySchema.Event.THREAD_NOTIFY:
                case BinarySchema.Event.THREAD_NOTIFY_ALL:
                case BinarySchema.Event.THREAD_UNPARK:
                case BinarySchema.Event.THREAD_INTERRUPT:
                case BinarySchema.Event.CLASS_INIT_END:
                case BinarySchema.Event.ATOMIC_WRITE:
                case BinarySchema.Event.ATOMIC_RMW:
                case BinarySchema.Event.ATOMIC_CAS:
                    return true;
                case BinarySchema.Event.FIELD_WRITE:
                    return (flags & BinarySchema.Flags.IS_VOLATILE) != 0;
                default:
                    return false;
            }
        }

        private long seq() {
            return seq;
        }

        private long[] toArray() {
            return new long[] { seq, roleId, packedType, objSite, objCount, data1, data2 };
        }
    }

    private static final class CaptureSemanticKey {
        private final long seq;
        private final long roleId;
        private final int packedType;

        private CaptureSemanticKey(long seq, long roleId, int packedType) {
            this.seq = seq;
            this.roleId = roleId;
            this.packedType = packedType;
        }

        private static CaptureSemanticKey of(SemanticObjectEvent event) {
            return new CaptureSemanticKey(event.seq(), event.roleId(), event.packedType());
        }

        private static CaptureSemanticKey of(RawEvent event) {
            return new CaptureSemanticKey(event.seq(), event.roleId, event.packedType);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CaptureSemanticKey)) {
                return false;
            }
            CaptureSemanticKey that = (CaptureSemanticKey) other;
            return seq == that.seq && roleId == that.roleId && packedType == that.packedType;
        }

        @Override
        public int hashCode() {
            long mixed = seq ^ (seq >>> 32) ^ roleId ^ (roleId >>> 32);
            return (int) mixed * 31 + packedType;
        }
    }
}
