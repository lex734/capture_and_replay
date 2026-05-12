package replay;

import common.BinarySchema;
import common.IdentityMapper;
import common.v1.FieldKey;
import common.v1.FieldInteractionDomain;
import common.v1.ReducedTraceRegistry;
import common.v1.ReplayConstraint;
import common.v1.SemanticIdentity;
import common.v1.SemanticObjectEvent;
import common.v1.SemanticTraceRegistry;
import java.lang.reflect.Field;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ReplayCoordinator {
    private static final long MATCH_WAIT_SLICE_MS = 100L;
    private static final long MATCH_TIMEOUT_MS = 2_000L;

    private static final Object controlLock = new Object();
    private static final ThreadLocal<Long> lastMatchedSeq = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<long[]> preparedSyncEvent = new ThreadLocal<>();

    private static final Map<Long, long[]> eventsBySeq = new HashMap<>();
    private static final Map<Integer, NavigableSet<Long>> pendingSeqsByRole = new HashMap<>();
    private static final Map<String, NavigableSet<Long>> pendingSeqsBySharedDomain = new HashMap<>();
    private static final NavigableSet<Long> pendingSeqs = new TreeSet<>();
    private static final Set<Long> matchedSeqs = new HashSet<>();
    private static final Set<String> sharedDomains = new HashSet<>();
    private static final Map<Integer, ArrayDeque<Long>> pendingThreadStartsByParent = new HashMap<>();
    private static final Map<Long, Integer> threadStartChildRoleBySeq = new HashMap<>();
    private static final Set<Integer> pendingRoles = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> startedRoles = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> activeRoles = ConcurrentHashMap.newKeySet();
    private static final AtomicLong releasedEpoch = new AtomicLong(0L);
    private static final AtomicLong pendingTargetEpoch = new AtomicLong(Long.MAX_VALUE);

    private static final ConcurrentHashMap<Integer, Thread> roleIdToThread = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> lastMatchedSeqByTraceObject = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> lastMatchedSeqByRole = new ConcurrentHashMap<>();

    private static volatile boolean hasDiverged = false;
    private static volatile String firstDivergenceInfo = null;
    private static volatile boolean degradedReplay = false;
    private static volatile String firstDegradationInfo = "";
    private static volatile boolean valueDivergedReplay = false;
    private static volatile String firstValueDivergenceInfo = "";
    private static volatile boolean unsupportedReplay = false;
    private static volatile String firstUnsupportedInfo = "";
    private static volatile boolean isIncomplete = false;
    private static volatile String incompleteReason = "";
    private static volatile String incompleteDetails = "";
    private static volatile int firstRoleInTrace = -1;

    public static boolean hasDiverged() {
        return hasDiverged;
    }

    static volatile boolean fidelityEnabled = false;
    static volatile String fidelityOutputPath = null;

    private static final AtomicLong totalEvents = new AtomicLong(0);
    private static final AtomicLong eventsMatched = new AtomicLong(0);
    private static final AtomicLong totalValuedEvents = new AtomicLong(0);
    private static final AtomicLong naturalAgreements = new AtomicLong(0);
    private static final AtomicLong injectedEvents = new AtomicLong(0);
    private static final AtomicLong noInjectDisagreements = new AtomicLong(0);

    private static final ConcurrentHashMap<Long, long[]> finalStateMap = new ConcurrentHashMap<>();

    private enum MatchState {
        FOUND,
        WAIT,
        NO_MATCH
    }

    private static final class MatchSelection {
        private final MatchState state;
        private final long[] event;
        private final boolean ambiguous;
        private final String reason;

        private MatchSelection(MatchState state, long[] event, boolean ambiguous, String reason) {
            this.state = state;
            this.event = event;
            this.ambiguous = ambiguous;
            this.reason = reason == null ? "" : reason;
        }
    }

    public static long getLastMatchedSeq() {
        return lastMatchedSeq.get();
    }

    public static void init(List<long[]> reducedEvents) {
        synchronized (controlLock) {
            eventsBySeq.clear();
            pendingSeqsByRole.clear();
            pendingSeqsBySharedDomain.clear();
            pendingSeqs.clear();
            matchedSeqs.clear();
            sharedDomains.clear();
            pendingThreadStartsByParent.clear();
            threadStartChildRoleBySeq.clear();
            pendingRoles.clear();
            startedRoles.clear();
            activeRoles.clear();
            releasedEpoch.set(0L);
            pendingTargetEpoch.set(Long.MAX_VALUE);
            roleIdToThread.clear();
            lastMatchedSeqByTraceObject.clear();
            lastMatchedSeqByRole.clear();
            SemanticIdentity.reset();

            hasDiverged = false;
            firstDivergenceInfo = null;
            degradedReplay = false;
            firstDegradationInfo = "";
            valueDivergedReplay = false;
            firstValueDivergenceInfo = "";
            unsupportedReplay = false;
            firstUnsupportedInfo = "";
            isIncomplete = false;
            incompleteReason = "";
            incompleteDetails = "";
            firstRoleInTrace = -1;

            totalEvents.set(0);
            eventsMatched.set(0);
            totalValuedEvents.set(0);
            naturalAgreements.set(0);
            injectedEvents.set(0);
            noInjectDisagreements.set(0);
            finalStateMap.clear();

            Map<Long, long[]> uniqueEventsBySeq = new LinkedHashMap<>();
            if (reducedEvents != null) {
                for (long[] event : reducedEvents) {
                    if (event == null || event.length < 7) {
                        continue;
                    }
                    long[] copied = copyEvent(event);
                    uniqueEventsBySeq.putIfAbsent(copied[0], copied);
                }
            }

            ArrayList<long[]> allEvents = new ArrayList<>(uniqueEventsBySeq.values());
            allEvents.sort(Comparator.comparingLong(event -> event[0]));
            totalEvents.set(allEvents.size());

            Map<String, Set<Integer>> rolesByDomain = new HashMap<>();
            for (long[] event : allEvents) {
                String domainId = ReducedTraceRegistry.lookupDomainId(event[0]);
                if (domainId == null || domainId.isEmpty()) {
                    continue;
                }
                rolesByDomain.computeIfAbsent(domainId, ignored -> new HashSet<>()).add((int) event[1]);
            }
            for (Map.Entry<String, Set<Integer>> entry : rolesByDomain.entrySet()) {
                if (entry.getValue().size() > 1) {
                    sharedDomains.add(entry.getKey());
                }
            }

            for (long[] event : allEvents) {
                long seq = event[0];
                int roleId = (int) event[1];
                int baseType = (int) event[2] & 0xFF;
                String domainId = ReducedTraceRegistry.lookupDomainId(seq);

                if (firstRoleInTrace == -1) {
                    firstRoleInTrace = roleId;
                }

                eventsBySeq.put(seq, event);
                pendingSeqs.add(seq);
                pendingSeqsByRole.computeIfAbsent(roleId, ignored -> new TreeSet<>()).add(seq);
                if (domainId != null && sharedDomains.contains(domainId)) {
                    pendingSeqsBySharedDomain.computeIfAbsent(domainId, ignored -> new TreeSet<>()).add(seq);
                }
                pendingRoles.add(roleId);

                if (baseType == BinarySchema.Event.THREAD_START) {
                    pendingThreadStartsByParent.computeIfAbsent(roleId, ignored -> new ArrayDeque<>()).add(seq);
                    threadStartChildRoleBySeq.put(seq, (int) event[5]);
                }

                if (fidelityEnabled && isWriteEvent(baseType)) {
                    long key = replayLocationKey(event);
                    long value = ((long) (int) event[5] << 32) | ((int) event[6] & 0xFFFFFFFFL);
                    finalStateMap.compute(key, (ignored, current) ->
                            current == null ? new long[] { value, Long.MIN_VALUE } : new long[] { value, current[1] });
                }
            }
        }
    }

    public static void registerMainThread(long mainTid) {
        if (firstRoleInTrace == -1) {
            return;
        }
        IdentityMapper.preAssignRole(mainTid, firstRoleInTrace);
        pendingRoles.remove(firstRoleInTrace);
        activeRoles.add(firstRoleInTrace);
        roleIdToThread.put(firstRoleInTrace, Thread.currentThread());
        debug(String.format("[Replay] main thread registered as role=%d", firstRoleInTrace));
    }

    public static int peekNextPendingRole() {
        synchronized (controlLock) {
            long bestSeq = Long.MAX_VALUE;
            int bestRole = -1;
            for (Map.Entry<Integer, NavigableSet<Long>> entry : pendingSeqsByRole.entrySet()) {
                Long seq = entry.getValue().isEmpty() ? null : entry.getValue().first();
                if (seq != null && seq < bestSeq) {
                    bestSeq = seq;
                    bestRole = entry.getKey();
                }
            }
            return bestRole;
        }
    }

    public static int peekChildRoleFromThreadStart(int parentRoleId) {
        synchronized (controlLock) {
            ArrayDeque<Long> queue = pendingThreadStartsByParent.get(parentRoleId);
            if (queue == null) {
                return -1;
            }
            while (!queue.isEmpty() && !pendingSeqs.contains(queue.peek())) {
                queue.poll();
            }
            Long seq = queue.peek();
            return seq == null ? -1 : threadStartChildRoleBySeq.getOrDefault(seq, -1);
        }
    }

    public static void prebindStableRoots(ClassLoader loader, Set<String> loadedClassNames) {
        Map<FieldKey, long[]> firstStaticObjectEvents = new HashMap<>();
        synchronized (controlLock) {
            for (Long seq : pendingSeqs) {
                SemanticObjectEvent objectEvent = SemanticTraceRegistry.lookupObjectEvent(seq);
                long[] rawEvent = eventsBySeq.get(seq);
                if (objectEvent == null || rawEvent == null) {
                    continue;
                }
                if (!objectEvent.isField()
                        || !objectEvent.isStatic()
                        || !objectEvent.isObjectValued()
                        || objectEvent.fieldKey() == null) {
                    continue;
                }
                int traceValueSite = (int) rawEvent[5];
                int traceValueCount = (int) rawEvent[6];
                if (traceValueSite == 0 && traceValueCount == 0) {
                    continue;
                }
                firstStaticObjectEvents.putIfAbsent(objectEvent.fieldKey(), rawEvent);
            }
        }

        for (Map.Entry<FieldKey, long[]> entry : firstStaticObjectEvents.entrySet()) {
            tryPrebindStaticFieldValue(entry.getKey(), entry.getValue(), loader, loadedClassNames);
        }
    }

    public static void checkIn(int roleId) {
        if (roleId != -1 && pendingRoles.remove(roleId)) {
            startedRoles.add(roleId);
            roleIdToThread.put(roleId, Thread.currentThread());
            debug(String.format("[Replay] role=%d checked in and is started", roleId));
            synchronized (controlLock) {
                controlLock.notifyAll();
            }
        }
    }

    public static void reportThreadDead(int roleId) {
        synchronized (controlLock) {
            NavigableSet<Long> rolePending = pendingSeqsByRole.get(roleId);
            if (rolePending != null && !rolePending.isEmpty()) {
                reportDivergence(roleId, "thread exited with " + rolePending.size() + " unconsumed replay events");
            }
            activeRoles.remove(roleId);
            startedRoles.remove(roleId);
            pendingRoles.remove(roleId);
            controlLock.notifyAll();
        }
    }

    private static void activateRole(int roleId) {
        if (roleId == -1 || activeRoles.contains(roleId)) {
            return;
        }
        synchronized (controlLock) {
            if (activeRoles.contains(roleId)) {
                return;
            }
            pendingRoles.remove(roleId);
            startedRoles.remove(roleId);
            activeRoles.add(roleId);
            roleIdToThread.put(roleId, Thread.currentThread());
            debug(String.format("[Replay] activating role=%d", roleId));
            controlLock.notifyAll();
        }
    }

    public static void awaitTurn(int roleId, int packedType, int objSite, int objCount, Object runtimeObject, int data) {
        long[] event = awaitSemanticEvent(roleId, packedType, objSite, objCount, runtimeObject, null, data);
        if (event == null) {
            return;
        }
        lastMatchedSeq.set(event[0]);
    }

    public static void prepareSyncTurn(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            int data) {
        long[] event = awaitPreparedSemanticEvent(roleId, packedType, objSite, objCount, runtimeObject, null, data);
        if (event == null) {
            return;
        }
        preparedSyncEvent.set(event);
        lastMatchedSeq.set(event[0]);
    }

    public static void completePreparedSyncTurn() {
        long[] event = preparedSyncEvent.get();
        if (event == null) {
            return;
        }
        preparedSyncEvent.remove();
        synchronized (controlLock) {
            consumeEvent(event);
        }
    }

    public static int awaitTurnInt(int roleId, int packedType, int objSite, int objCount, Object runtimeObject) {
        return awaitTurnInt(roleId, packedType, objSite, objCount, runtimeObject, 0);
    }

    public static int awaitTurnInt(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            int naturalValue) {
        long[] event = awaitSemanticEvent(roleId, packedType, objSite, objCount, runtimeObject, null, objCount);
        if (event == null) {
            return naturalValue;
        }
        int traceValue = (int) event[6];
        trackFidelityInt(packedType, objSite, objCount, naturalValue, event);
        if (naturalValue != traceValue) {
            recordDegradation("value injection on nondeterministic int source");
            recordValueDivergence("nondeterministic int source diverged from runtime value");
        }
        return traceValue;
    }

    public static long awaitTurnLong(int roleId, int packedType, int objSite, int objCount, Object runtimeObject) {
        return awaitTurnLong(roleId, packedType, objSite, objCount, runtimeObject, 0L);
    }

    public static long awaitTurnLong(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            long naturalValue) {
        long[] event = awaitSemanticEvent(roleId, packedType, objSite, objCount, runtimeObject, null, objCount);
        if (event == null) {
            return naturalValue;
        }
        long traceValue = ((long) event[5] << 32) | (event[6] & 0xFFFFFFFFL);
        trackFidelityLong(packedType, objSite, objCount, naturalValue, event);
        if (naturalValue != traceValue) {
            recordDegradation("value injection on nondeterministic long source");
            recordValueDivergence("nondeterministic long source diverged from runtime value");
        }
        return traceValue;
    }

    public static Object awaitTurnObj(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            Object naturalValue) {
        long[] event = awaitSemanticEvent(roleId, packedType, objSite, objCount, runtimeObject, null, objCount);
        if (event == null) {
            return naturalValue;
        }
        Object traceValue = SemanticIdentity.resolveRuntime((int) event[5], (int) event[6]);
        if (traceValue == null && naturalValue != null) {
            reportDivergence(roleId, "required traced object value is no longer realizable at replay event "
                    + event[0] + semanticDetail(event));
            return naturalValue;
        }
        trackFidelityObj(packedType, objSite, objCount, naturalValue, event);
        if (traceValue != naturalValue) {
            recordDegradation("value injection on object-valued replay event");
            recordValueDivergence("object-valued replay event diverged from runtime value");
        }
        return traceValue;
    }

    public static int awaitTurnCasInt(int roleId, int packedType, int objSite, int objCount, Object runtimeObject) {
        long[] event = doAwaitTurnValued(roleId, packedType, objSite, objCount, runtimeObject, null);
        if (event == null) {
            return 0;
        }
        recordDegradation("atomic CAS result injection");
        recordValueDivergence("atomic CAS int result diverged from runtime value");
        return (int) event[6];
    }

    public static long awaitTurnCasLong(int roleId, int packedType, int objSite, int objCount, Object runtimeObject) {
        long[] event = doAwaitTurnValued(roleId, packedType, objSite, objCount, runtimeObject, null);
        if (event == null) {
            return 0L;
        }
        recordDegradation("atomic CAS result injection");
        recordValueDivergence("atomic CAS long result diverged from runtime value");
        return ((long) event[5] << 32) | (event[6] & 0xFFFFFFFFL);
    }

    public static Object awaitTurnCasObj(int roleId, int packedType, int objSite, int objCount, Object runtimeObject) {
        long[] event = doAwaitTurnValued(roleId, packedType, objSite, objCount, runtimeObject, null);
        if (event == null) {
            return null;
        }
        Object traceValue = SemanticIdentity.resolveRuntime((int) event[5], (int) event[6]);
        if (traceValue == null && ((int) event[5] != 0 || (int) event[6] != 0)) {
            reportDivergence(roleId, "required traced CAS object result is no longer realizable at replay event "
                    + event[0] + semanticDetail(event));
            return null;
        }
        recordDegradation("atomic CAS object result injection");
        recordValueDivergence("atomic CAS object result diverged from runtime value");
        return traceValue;
    }

    public static int awaitTurnRmwInt(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            int naturalValue) {
        long[] event = doAwaitTurnValued(roleId, packedType, objSite, objCount, runtimeObject, null);
        trackFidelityInt(packedType, objSite, objCount, naturalValue, event);
        if (event == null) {
            return naturalValue;
        }
        int traceValue = (int) event[6];
        if (naturalValue != traceValue) {
            recordUnsupported("atomic RMW diverged from trace value");
        }
        return naturalValue;
    }

    public static long awaitTurnRmwLong(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            long naturalValue) {
        long[] event = doAwaitTurnValued(roleId, packedType, objSite, objCount, runtimeObject, null);
        trackFidelityLong(packedType, objSite, objCount, naturalValue, event);
        if (event == null) {
            return naturalValue;
        }
        long traceValue = ((long) event[5] << 32) | (event[6] & 0xFFFFFFFFL);
        if (naturalValue != traceValue) {
            recordUnsupported("atomic RMW diverged from trace value");
        }
        return naturalValue;
    }

    public static Object awaitTurnRmwObj(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            Object naturalValue) {
        long[] event = doAwaitTurnValued(roleId, packedType, objSite, objCount, runtimeObject, null);
        trackFidelityObj(packedType, objSite, objCount, naturalValue, event);
        if (event == null) {
            return naturalValue;
        }
        Object traceValue = SemanticIdentity.resolveRuntime((int) event[5], (int) event[6]);
        if (traceValue == null && ((int) event[5] != 0 || (int) event[6] != 0)) {
            reportDivergence(roleId, "required traced RMW object value is no longer realizable at replay event "
                    + event[0] + semanticDetail(event));
            return naturalValue;
        }
        if (naturalValue != traceValue) {
            recordUnsupported("atomic object RMW diverged from trace value");
        }
        return naturalValue;
    }

    public static void recordNoInjectDisagreement() {
        if (!fidelityEnabled) {
            return;
        }
        noInjectDisagreements.incrementAndGet();
    }

    public static long[] doAwaitTurnValued(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            FieldKey actualFieldKey) {
        return awaitSemanticEvent(roleId, packedType, objSite, objCount, runtimeObject, actualFieldKey, 0);
    }

    public static int awaitTurnFieldInt(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            FieldKey actualFieldKey, int naturalValue) {
        long[] event = doAwaitTurnValued(roleId, packedType, objSite, objCount, runtimeObject, actualFieldKey);
        trackFidelityInt(packedType, objSite, objCount, naturalValue, event);
        if (event == null) {
            return naturalValue;
        }
        int traceValue = (int) event[6];
        if (naturalValue != traceValue) {
            recordDegradation("plain read value injection");
            recordValueDivergence("plain valued replay event diverged from runtime value");
            if (shouldInjectReadValue(packedType)) {
                return traceValue;
            }
        }
        return naturalValue;
    }

    public static long awaitTurnFieldLong(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            FieldKey actualFieldKey, long naturalValue) {
        long[] event = doAwaitTurnValued(roleId, packedType, objSite, objCount, runtimeObject, actualFieldKey);
        trackFidelityLong(packedType, objSite, objCount, naturalValue, event);
        if (event == null) {
            return naturalValue;
        }
        long traceValue = ((long) event[5] << 32) | (event[6] & 0xFFFFFFFFL);
        if (naturalValue != traceValue) {
            recordDegradation("plain read value injection");
            recordValueDivergence("plain valued replay event diverged from runtime value");
            if (shouldInjectReadValue(packedType)) {
                return traceValue;
            }
        }
        return naturalValue;
    }

    public static Object awaitTurnFieldObj(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            FieldKey actualFieldKey, Object naturalValue) {
        long[] event = doAwaitTurnValued(roleId, packedType, objSite, objCount, runtimeObject, actualFieldKey);
        if (event == null) {
            return naturalValue;
        }
        Object traceValue = SemanticIdentity.resolveRuntime((int) event[5], (int) event[6]);
        boolean objectReferenceRequired = ((int) event[5] != 0 || (int) event[6] != 0);
        boolean isRead = (((int) event[2] & 0xFF) == BinarySchema.Event.FIELD_READ)
                || (((int) event[2] & 0xFF) == BinarySchema.Event.ARRAY_READ)
                || (((int) event[2] & 0xFF) == BinarySchema.Event.ATOMIC_READ);
        if (traceValue == null && objectReferenceRequired) {
            if (naturalValue != null) {
                // The first observed object-valued interaction may be a publication
                // write (for example a static field storing a freshly created
                // AtomicInteger). Use that concrete runtime value to establish the
                // trace-object binding instead of diverging immediately.
                SemanticIdentity.bindValueIdentity((int) event[5], (int) event[6], naturalValue,
                        semanticShapeKey(event, actualFieldKey));
                traceValue = naturalValue;
            } else {
                reportDivergence(roleId, "required traced object value is no longer realizable at replay event "
                        + event[0] + semanticDetail(event));
                return naturalValue;
            }
        }
        trackFidelityObj(packedType, objSite, objCount, naturalValue, event);
        if (naturalValue != traceValue) {
            recordDegradation("plain object-read value injection");
            recordValueDivergence("object-valued replay event diverged from runtime value");
            if (shouldInjectReadValue(packedType)) {
                return traceValue;
            }
        }
        return naturalValue;
    }

    public static int awaitTurnArrayInt(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            int naturalValue) {
        return awaitTurnFieldInt(roleId, packedType, objSite, objCount, runtimeObject, null, naturalValue);
    }

    public static long awaitTurnArrayLong(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            long naturalValue) {
        return awaitTurnFieldLong(roleId, packedType, objSite, objCount, runtimeObject, null, naturalValue);
    }

    public static Object awaitTurnArrayObj(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            Object naturalValue) {
        return awaitTurnFieldObj(roleId, packedType, objSite, objCount, runtimeObject, null, naturalValue);
    }

    public static void printFidelityReport() {
        synchronized (controlLock) {
            if (!hasDiverged && !pendingSeqs.isEmpty()) {
                isIncomplete = true;
                incompleteReason = unsupportedReplay ? "unsupported_tail" : "unconsumed_tail";
                incompleteDetails = pendingEventSummary();
            } else if (!hasDiverged) {
                unsupportedReplay = false;
                firstUnsupportedInfo = "";
            }
        }

        long total = totalValuedEvents.get();
        long agreed = naturalAgreements.get();
        long injected = injectedEvents.get();
        long noInject = noInjectDisagreements.get();

        int locs = finalStateMap.size();
        int matched = 0;
        int unseen = 0;
        for (long[] value : finalStateMap.values()) {
            if (value[1] == Long.MIN_VALUE) {
                unseen++;
                continue;
            }
            if (value[1] == value[0]) {
                matched++;
            }
        }
        int seen = locs - unseen;
        boolean match = (seen > 0) && (matched == seen);

        Properties properties = new Properties();
        properties.setProperty("match", String.valueOf(match));
        properties.setProperty("structural_divergence", String.valueOf(hasDiverged));
        properties.setProperty("structural_divergence_details", firstDivergenceInfo == null ? "" : firstDivergenceInfo);
        properties.setProperty("degraded", String.valueOf(degradedReplay));
        properties.setProperty("degraded_details", firstDegradationInfo);
        properties.setProperty("value_diverged", String.valueOf(valueDivergedReplay));
        properties.setProperty("value_diverged_details", firstValueDivergenceInfo);
        properties.setProperty("unsupported", String.valueOf(unsupportedReplay));
        properties.setProperty("unsupported_details", firstUnsupportedInfo);
        properties.setProperty("incomplete", String.valueOf(isIncomplete));
        properties.setProperty("incomplete_reason", incompleteReason);
        properties.setProperty("incomplete_details", incompleteDetails);
        properties.setProperty("events_matched", String.valueOf(eventsMatched.get()));
        properties.setProperty("events_total", String.valueOf(totalEvents.get()));
        properties.setProperty("valued_events", String.valueOf(total));
        properties.setProperty("natural_agreements", String.valueOf(agreed));
        properties.setProperty("injections", String.valueOf(injected));
        properties.setProperty("no_inject_disagreements", String.valueOf(noInject));
        properties.setProperty("locations_matched", String.valueOf(matched));
        properties.setProperty("locations_total", String.valueOf(locs));
        properties.setProperty("locations_unseen", String.valueOf(unseen));

        try (Writer writer = new FileWriter(fidelityOutputPath)) {
            properties.store(writer, "Replay Fidelity Result");
        } catch (IOException e) {
            System.err.println("[FIDELITY] Failed to write result file: " + e.getMessage());
        }
    }

    private static long[] awaitSemanticEvent(int roleId, int packedType, int objSite, int objCount, Object runtimeObject,
            FieldKey actualFieldKey, int nondeterministicSourceKey) {
        activateRole(roleId);
        roleIdToThread.put(roleId, Thread.currentThread());
        if (!shouldSynchronizeAtEvent(packedType)) {
            long deadline = System.currentTimeMillis() + MATCH_TIMEOUT_MS;
            synchronized (controlLock) {
                while (true) {
                    if (hasDiverged) {
                        return null;
                    }
                    failFastOnDeadPendingRole();
                    Long headSeq = headSeq(roleId);
                    if (headSeq == null) {
                        debug(String.format("[Replay] role=%d has no pending non-release head", roleId));
                        return null;
                    }
                    MatchSelection selection = selectCandidate(roleId, packedType, objSite, objCount, runtimeObject,
                            actualFieldKey, nondeterministicSourceKey);
                    if (selection.state == MatchState.FOUND) {
                        long headEpoch = ReducedTraceRegistry.lookupEpoch(headSeq);
                        if (headEpoch > releasedEpoch.get()) {
                            debug(String.format("[Replay] role=%d waiting for epoch gate headSeq=%d headEpoch=%d released=%d",
                                    roleId, headSeq, headEpoch, releasedEpoch.get()));
                            try {
                                controlLock.wait(Math.min(MATCH_WAIT_SLICE_MS,
                                        Math.max(1L, deadline - System.currentTimeMillis())));
                                continue;
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return null;
                            }
                        }
                        if (selection.ambiguous) {
                            recordDegradation("ambiguous semantic match resolved to earliest candidate");
                        }
                        if (!commitBinding(selection.event, runtimeObject, actualFieldKey)) {
                            reportDivergence(roleId, "conflicting object binding during replay match");
                            return null;
                        }
                        consumeEvent(selection.event);
                        return selection.event;
                    }
                    if (selection.state == MatchState.NO_MATCH) {
                        debug(String.format("[Replay] role=%d non-release no-match: %s", roleId, selection.reason));
                        return null;
                    }
                    if (selection.state == MatchState.WAIT) {
                        if (selection.reason.startsWith("object lifecycle position")) {
                            debug(String.format("[Replay] role=%d non-release deferring: %s", roleId, selection.reason));
                            return null;
                        }
                        debug(String.format("[Replay] role=%d non-release waiting condition treated as pass-through: %s",
                                roleId, selection.reason));
                        return null;
                    }
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0L) {
                        debug(String.format("[Replay] role=%d non-release timeout waiting: %s", roleId, selection.reason));
                        return null;
                    }
                    try {
                        controlLock.wait(Math.min(MATCH_WAIT_SLICE_MS, remaining));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        long deadline = System.currentTimeMillis() + MATCH_TIMEOUT_MS;
        synchronized (controlLock) {
            while (true) {
                if (hasDiverged) {
                    return null;
                }
                failFastOnDeadPendingRole();
                Long headSeq = headSeq(roleId);
                if (headSeq == null) {
                    debug(String.format("[Replay] role=%d has no pending synchronized head", roleId));
                    return null;
                }
                long headEpoch = ReducedTraceRegistry.lookupEpoch(headSeq);
                if (headEpoch > releasedEpoch.get()) {
                    int headPackedType = (int) eventsBySeq.get(headSeq)[2];
                    if (shouldSynchronizeAtEvent(headPackedType)) {
                        long lowestPending = pendingTargetEpoch.accumulateAndGet(headEpoch, Math::min);
                        if (lowestPending == headEpoch && !isAnyRoleBehind(headEpoch)) {
                            debug(String.format("[Replay] role=%d advancing released epoch to %d via headSeq=%d",
                                    roleId, headEpoch, headSeq));
                            releasedEpoch.set(headEpoch);
                            pendingTargetEpoch.compareAndSet(headEpoch, Long.MAX_VALUE);
                        } else {
                            debug(String.format("[Replay] role=%d waiting to advance epoch %d (lowestPending=%d)",
                                    roleId, headEpoch, lowestPending));
                            try {
                                controlLock.wait(Math.min(MATCH_WAIT_SLICE_MS, Math.max(1L, deadline - System.currentTimeMillis())));
                                continue;
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return null;
                            }
                        }
                    } else {
                        debug(String.format("[Replay] role=%d non-release headSeq=%d waiting for released epoch %d -> %d",
                                roleId, headSeq, releasedEpoch.get(), headEpoch));
                        try {
                            controlLock.wait(Math.min(MATCH_WAIT_SLICE_MS, Math.max(1L, deadline - System.currentTimeMillis())));
                            continue;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    }
                }

                MatchSelection selection = selectCandidate(roleId, packedType, objSite, objCount, runtimeObject,
                        actualFieldKey, nondeterministicSourceKey);
                if (selection.state == MatchState.FOUND) {
                    if (selection.ambiguous) {
                        recordDegradation("ambiguous semantic match resolved to earliest candidate");
                    }
                    if (!commitBinding(selection.event, runtimeObject, actualFieldKey)) {
                        reportDivergence(roleId, "conflicting object binding during replay match");
                        return null;
                    }
                    consumeEvent(selection.event);
                    return selection.event;
                }

                if (selection.state == MatchState.NO_MATCH) {
                    return null;
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    recordUnsupported(selection.reason.isEmpty()
                            ? "timed out waiting for predecessor constraints to become realizable"
                            : selection.reason);
                    return null;
                }
                try {
                    controlLock.wait(Math.min(MATCH_WAIT_SLICE_MS, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    recordUnsupported("interrupted while waiting for replay constraints");
                    return null;
                }
            }
        }
    }

    private static long[] awaitPreparedSemanticEvent(int roleId, int packedType, int objSite, int objCount,
            Object runtimeObject, FieldKey actualFieldKey, int nondeterministicSourceKey) {
        activateRole(roleId);
        roleIdToThread.put(roleId, Thread.currentThread());
        long deadline = System.currentTimeMillis() + MATCH_TIMEOUT_MS;
        synchronized (controlLock) {
            while (true) {
                if (hasDiverged) {
                    return null;
                }
                failFastOnDeadPendingRole();
                Long headSeq = headSeq(roleId);
                if (headSeq == null) {
                    debug(String.format("[Replay] role=%d has no pending prepared sync head", roleId));
                    return null;
                }
                long headEpoch = ReducedTraceRegistry.lookupEpoch(headSeq);
                if (headEpoch > releasedEpoch.get()) {
                    int headPackedType = (int) eventsBySeq.get(headSeq)[2];
                    if (shouldSynchronizeAtEvent(headPackedType)) {
                        long lowestPending = pendingTargetEpoch.accumulateAndGet(headEpoch, Math::min);
                        if (lowestPending == headEpoch && !isAnyRoleBehind(headEpoch)) {
                            debug(String.format("[Replay] role=%d advancing released epoch to %d via headSeq=%d",
                                    roleId, headEpoch, headSeq));
                            releasedEpoch.set(headEpoch);
                            pendingTargetEpoch.compareAndSet(headEpoch, Long.MAX_VALUE);
                        } else {
                            try {
                                controlLock.wait(Math.min(MATCH_WAIT_SLICE_MS,
                                        Math.max(1L, deadline - System.currentTimeMillis())));
                                continue;
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return null;
                            }
                        }
                    } else {
                        try {
                            controlLock.wait(Math.min(MATCH_WAIT_SLICE_MS,
                                    Math.max(1L, deadline - System.currentTimeMillis())));
                            continue;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    }
                }

                MatchSelection selection = selectCandidate(roleId, packedType, objSite, objCount, runtimeObject,
                        actualFieldKey, nondeterministicSourceKey);
                if (selection.state == MatchState.FOUND) {
                    if (selection.ambiguous) {
                        recordDegradation("ambiguous semantic match resolved to earliest candidate");
                    }
                    if (!commitBinding(selection.event, runtimeObject, actualFieldKey)) {
                        reportDivergence(roleId, "conflicting object binding during replay match");
                        return null;
                    }
                    return selection.event;
                }
                if (selection.state == MatchState.NO_MATCH) {
                    debug(String.format("[Replay] role=%d prepared sync no-match: %s", roleId, selection.reason));
                    return null;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    recordUnsupported(selection.reason.isEmpty()
                            ? "timed out waiting for replay constraints to become realizable"
                            : selection.reason);
                    return null;
                }
                try {
                    controlLock.wait(Math.min(MATCH_WAIT_SLICE_MS, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    private static MatchSelection selectCandidate(int roleId, int packedType, int objSite, int objCount,
            Object runtimeObject, FieldKey actualFieldKey, int nondeterministicSourceKey) {
        NavigableSet<Long> rolePending = pendingSeqsByRole.get(roleId);
        if (rolePending == null || rolePending.isEmpty()) {
            return new MatchSelection(MatchState.NO_MATCH, null, false, "no pending replay events for role " + roleId);
        }
        Long headSeq = rolePending.first();
        long[] head = eventsBySeq.get(headSeq);
        if (head == null) {
            return new MatchSelection(MatchState.NO_MATCH, null, false, "missing replay event for role head " + headSeq);
        }

        boolean headShapeMatches = samePackedShape(head, packedType)
                && sameSemanticEventContext(head, actualFieldKey, objCount, nondeterministicSourceKey)
                && bindingCompatible(head, runtimeObject, objCount, actualFieldKey);
        if (headShapeMatches) {
            if (shouldEnforcePredecessors(head) && !predecessorsSatisfied(headSeq)) {
                return new MatchSelection(MatchState.WAIT, null, false,
                        "predecessor constraints for seq=" + headSeq + " are not yet satisfied");
            }
            return new MatchSelection(MatchState.FOUND, head, false, "");
        }

        if (shouldBlockForRelevantObject(runtimeObject)) {
            return new MatchSelection(MatchState.WAIT, null, false,
                    "bound relevant object awaiting exact role-head seq=" + headSeq);
        }

        boolean headBaseShapeMatches = samePackedShape(head, packedType);
        boolean headContextMatches = headBaseShapeMatches
                && sameSemanticEventContext(head, actualFieldKey, objCount, nondeterministicSourceKey);
        if (headContextMatches) {
            return new MatchSelection(MatchState.NO_MATCH, null, false,
                    "runtime event matched role-head shape/context but could not bind to seq=" + headSeq);
        }
        if (headBaseShapeMatches) {
            return new MatchSelection(MatchState.NO_MATCH, null, false,
                    "runtime event matched role-head base type but not role-head semantic context seq=" + headSeq);
        }
        return new MatchSelection(MatchState.NO_MATCH, null, false,
                "runtime event does not match current role-head seq=" + headSeq);
    }

    private static boolean shouldEnforcePredecessors(long[] event) {
        if (event == null || event.length < 3) {
            return false;
        }
        return shouldSynchronizeAtEvent((int) event[2]);
    }

    private static boolean samePackedShape(long[] expected, int actualPackedType) {
        int expectedPackedType = (int) expected[2];
        int expectedBaseType = expectedPackedType & 0xFF;
        int actualBaseType = actualPackedType & 0xFF;
        if (expectedBaseType == BinarySchema.Event.FIELD_READ
                || expectedBaseType == BinarySchema.Event.FIELD_WRITE
                || expectedBaseType == BinarySchema.Event.ARRAY_READ
                || expectedBaseType == BinarySchema.Event.ARRAY_WRITE
                || expectedBaseType == BinarySchema.Event.ATOMIC_READ
                || expectedBaseType == BinarySchema.Event.ATOMIC_WRITE
                || expectedBaseType == BinarySchema.Event.ATOMIC_RMW
                || expectedBaseType == BinarySchema.Event.ATOMIC_CAS) {
            return (expectedPackedType & 0xFFFF) == (actualPackedType & 0xFFFF);
        }
        return expectedBaseType == actualBaseType;
    }

    private static boolean sameSemanticEventContext(long[] expected, FieldKey actualFieldKey,
            int actualObjCount, int sourceContextKey) {
        SemanticObjectEvent expectedObject = SemanticTraceRegistry.lookupObjectEvent(expected[0]);
        if (expectedObject == null) {
            return sameNondeterministicSourceKey(expected, sourceContextKey)
                    && (actualFieldKey == null || sameFieldKeyFallback(expected, actualFieldKey));
        }

        if (expectedObject.fieldKey() != null && actualFieldKey != null
                && !expectedObject.fieldKey().equals(actualFieldKey)) {
            return false;
        }
        if (expectedObject.sourceSiteId() >= 0
                && sourceContextKey != 0
                && expectedObject.sourceSiteId() != sourceContextKey) {
            return false;
        }
        if ((expectedObject.isArray() || expectedObject.isAtomic()) && expectedObject.index() >= 0
                && expectedObject.index() != actualObjCount) {
            return false;
        }
        if (expectedObject.isNondeterministic()) {
            return sameNondeterministicSourceKey(expected, sourceContextKey);
        }
        return true;
    }

    private static boolean sameFieldKeyFallback(long[] expected, FieldKey actualFieldKey) {
        if (actualFieldKey == null) {
            return true;
        }
        SemanticObjectEvent expectedObject = SemanticTraceRegistry.lookupObjectEvent(expected[0]);
        return expectedObject == null
                || expectedObject.fieldKey() == null
                || expectedObject.fieldKey().equals(actualFieldKey);
    }

    private static boolean sameNondeterministicSourceKey(long[] expected, int actualSourceKey) {
        int baseType = (int) expected[2] & 0xFF;
        if (baseType != BinarySchema.Event.NONDETERMINISTIC_INT
                && baseType != BinarySchema.Event.NONDETERMINISTIC_LONG) {
            return true;
        }
        return actualSourceKey == (int) expected[4];
    }

    private static boolean bindingCompatible(long[] expected, Object runtimeObject, int actualObjCount,
            FieldKey actualFieldKey) {
        int packedType = (int) expected[2];
        int baseType = packedType & 0xFF;
        SemanticObjectEvent expectedObject = SemanticTraceRegistry.lookupObjectEvent(expected[0]);
        String domainId = ReducedTraceRegistry.lookupDomainId(expected[0]);
        long epoch = ReducedTraceRegistry.lookupEpoch(expected[0]);

        if (baseType == BinarySchema.Event.NONDETERMINISTIC_INT
                || baseType == BinarySchema.Event.NONDETERMINISTIC_LONG) {
            return true;
        }

        int traceSite;
        int traceCount;
        if (isArrayScoped(packedType)) {
            if (actualObjCount != (int) expected[4]) {
                return false;
            }
            traceSite = packedType >>> 16;
            traceCount = (int) expected[3];
        } else {
            traceSite = (int) expected[3];
            traceCount = (int) expected[4];
        }

        if (runtimeObject == null) {
            return traceSite == 0 && traceCount == 0;
        }

        return SemanticIdentity.canMatch(traceSite, traceCount, runtimeObject,
                expectedObject, domainId, expected[0], epoch);
    }

    private static boolean commitBinding(long[] expected, Object runtimeObject, FieldKey actualFieldKey) {
        int packedType = (int) expected[2];
        int baseType = packedType & 0xFF;
        SemanticObjectEvent expectedObject = SemanticTraceRegistry.lookupObjectEvent(expected[0]);
        String domainId = ReducedTraceRegistry.lookupDomainId(expected[0]);
        long epoch = ReducedTraceRegistry.lookupEpoch(expected[0]);
        if (baseType == BinarySchema.Event.NONDETERMINISTIC_INT
                || baseType == BinarySchema.Event.NONDETERMINISTIC_LONG) {
            return true;
        }

        int traceSite;
        int traceCount;
        if (isArrayScoped(packedType)) {
            traceSite = packedType >>> 16;
            traceCount = (int) expected[3];
        } else {
            traceSite = (int) expected[3];
            traceCount = (int) expected[4];
        }
        boolean committed = SemanticIdentity.commitMatch(traceSite, traceCount, runtimeObject,
                expectedObject, domainId, expected[0], epoch);
        if (committed && runtimeObject != null) {
            IdentityMapper.registerByBirthId(traceSite, traceCount, runtimeObject);
        }
        return committed;
    }

    private static boolean predecessorsSatisfied(long seq) {
        List<ReplayConstraint> constraints = ReducedTraceRegistry.lookupConstraints(seq);
        for (ReplayConstraint constraint : constraints) {
            if (!matchedSeqs.contains(constraint.predecessorSeq())) {
                return false;
            }
        }
        return true;
    }

    private static boolean lifecyclePositionCompatible(long[] event) {
        int baseType = (int) event[2] & 0xFF;
        if (baseType == BinarySchema.Event.FIELD_READ
                || baseType == BinarySchema.Event.FIELD_WRITE
                || baseType == BinarySchema.Event.ARRAY_READ
                || baseType == BinarySchema.Event.ARRAY_WRITE
                || baseType == BinarySchema.Event.ATOMIC_READ
                || baseType == BinarySchema.Event.ATOMIC_WRITE
                || baseType == BinarySchema.Event.ATOMIC_RMW
                || baseType == BinarySchema.Event.ATOMIC_CAS) {
            return true;
        }
        long lifecycleKey = traceLifecycleKey(event);
        if (lifecycleKey == Long.MIN_VALUE) {
            return true;
        }
        Long previous = lastMatchedSeqByTraceObject.get(lifecycleKey);
        return previous == null || previous.longValue() < event[0];
    }

    private static void consumeEvent(long[] event) {
        long seq = event[0];
        int roleId = (int) event[1];
        long epoch = ReducedTraceRegistry.lookupEpoch(seq);
        debug(String.format("[Replay] consume seq=%d epoch=%d role=%d type=0x%04x%s",
                seq, epoch, roleId, (int) event[2], semanticDetail(event)));

        matchedSeqs.add(seq);
        pendingSeqs.remove(seq);
        NavigableSet<Long> rolePending = pendingSeqsByRole.get(roleId);
        if (rolePending != null) {
            rolePending.remove(seq);
            if (rolePending.isEmpty()) {
                pendingSeqsByRole.remove(roleId);
            }
        }
        String domainId = ReducedTraceRegistry.lookupDomainId(seq);
        if (domainId != null && sharedDomains.contains(domainId)) {
            NavigableSet<Long> domainPending = pendingSeqsBySharedDomain.get(domainId);
            if (domainPending != null) {
                domainPending.remove(seq);
                if (domainPending.isEmpty()) {
                    pendingSeqsBySharedDomain.remove(domainId);
                }
            }
        }

        lastMatchedSeq.set(seq);
        lastMatchedSeqByRole.put((long) roleId, seq);

        long lifecycleKey = traceLifecycleKey(event);
        if (lifecycleKey != Long.MIN_VALUE) {
            lastMatchedSeqByTraceObject.put(lifecycleKey, seq);
        }

        int baseType = (int) event[2] & 0xFF;
        if (baseType == BinarySchema.Event.THREAD_START) {
            ArrayDeque<Long> starts = pendingThreadStartsByParent.get(roleId);
            if (starts != null) {
                while (!starts.isEmpty() && starts.peek() != seq) {
                    if (!pendingSeqs.contains(starts.peek())) {
                        starts.poll();
                    } else {
                        break;
                    }
                }
                if (!starts.isEmpty() && starts.peek() == seq) {
                    starts.poll();
                }
            }
        }

        eventsMatched.incrementAndGet();
        if (shouldSynchronizeAtEvent((int) event[2])) {
            releasedEpoch.updateAndGet(current -> Math.max(current, epoch));
            pendingTargetEpoch.compareAndSet(epoch, Long.MAX_VALUE);
        }
        controlLock.notifyAll();
    }

    private static boolean shouldSynchronizeAtEvent(int packedType) {
        int baseType = packedType & 0xFF;
        if (baseType == BinarySchema.Event.FIELD_WRITE
                && (((packedType >>> 8) & BinarySchema.Flags.IS_VOLATILE) != 0)) {
            return true;
        }
        return baseType == BinarySchema.Event.MONITOR_EXIT
                || baseType == BinarySchema.Event.THREAD_START
                || baseType == BinarySchema.Event.THREAD_NOTIFY
                || baseType == BinarySchema.Event.THREAD_NOTIFY_ALL
                || baseType == BinarySchema.Event.THREAD_UNPARK
                || baseType == BinarySchema.Event.THREAD_INTERRUPT
                || baseType == BinarySchema.Event.CLASS_INIT_END
                || baseType == BinarySchema.Event.ATOMIC_WRITE
                || baseType == BinarySchema.Event.ATOMIC_RMW
                || baseType == BinarySchema.Event.ATOMIC_CAS;
    }

    private static boolean shouldInjectReadValue(int packedType) {
        int baseType = packedType & 0xFF;
        return baseType == BinarySchema.Event.FIELD_READ
                || baseType == BinarySchema.Event.ARRAY_READ
                || baseType == BinarySchema.Event.ATOMIC_READ;
    }

    private static Long headSeq(int roleId) {
        NavigableSet<Long> rolePending = pendingSeqsByRole.get(roleId);
        return (rolePending == null || rolePending.isEmpty()) ? null : rolePending.first();
    }

    private static boolean sharedDomainTurnReady(long[] event) {
        if (event == null) {
            return false;
        }
        String domainId = ReducedTraceRegistry.lookupDomainId(event[0]);
        if (domainId == null || !sharedDomains.contains(domainId)) {
            return true;
        }
        NavigableSet<Long> domainPending = pendingSeqsBySharedDomain.get(domainId);
        return domainPending != null
                && !domainPending.isEmpty()
                && domainPending.first().longValue() == event[0];
    }

    private static boolean shouldBlockForSharedDomainTurn(int packedType, Object runtimeObject,
            FieldKey actualFieldKey, int actualObjCount) {
        if (runtimeObject == null) {
            return false;
        }
        String actualDomainId = actualDomainIdForRuntimeEvent(packedType, runtimeObject, actualFieldKey, actualObjCount);
        if (actualDomainId == null || !sharedDomains.contains(actualDomainId)) {
            return false;
        }
        NavigableSet<Long> pendingForDomain = pendingSeqsBySharedDomain.get(actualDomainId);
        if (pendingForDomain == null || pendingForDomain.isEmpty()) {
            return false;
        }
        long headSeq = pendingForDomain.first();
        long[] head = eventsBySeq.get(headSeq);
        return head != null && !samePackedShape(head, packedType);
    }

    private static boolean shouldBlockForRelevantObject(Object runtimeObject) {
        if (runtimeObject == null) {
            return false;
        }
        long traceId = SemanticIdentity.lookupTraceId(runtimeObject);
        return traceId != Long.MIN_VALUE;
    }

    private static String actualDomainIdForRuntimeEvent(int packedType, Object runtimeObject,
            FieldKey actualFieldKey, int actualObjCount) {
        long traceId = SemanticIdentity.lookupTraceId(runtimeObject);
        if (traceId == Long.MIN_VALUE) {
            return null;
        }
        int traceSite = (int) (traceId >>> 32);
        int traceCount = (int) traceId;
        int baseType = packedType & 0xFF;
        if ((baseType == BinarySchema.Event.FIELD_READ || baseType == BinarySchema.Event.FIELD_WRITE)
                && actualFieldKey != null) {
            return "field:" + traceSite + ":" + traceCount + ":"
                    + actualFieldKey.owner().internalName() + "." + actualFieldKey.name() + ":" + actualFieldKey.descriptor();
        }
        if (baseType == BinarySchema.Event.ARRAY_READ || baseType == BinarySchema.Event.ARRAY_WRITE) {
            return "array:" + traceSite + ":" + traceCount + ":" + actualObjCount;
        }
        if (baseType == BinarySchema.Event.ATOMIC_READ
                || baseType == BinarySchema.Event.ATOMIC_WRITE
                || baseType == BinarySchema.Event.ATOMIC_RMW
                || baseType == BinarySchema.Event.ATOMIC_CAS) {
            return "atomic-object:" + traceSite + ":" + traceCount;
        }
        return null;
    }

    private static boolean isAnyRoleBehind(long targetEpoch) {
        for (Integer roleId : activeRoles) {
            if (isRoleBehind(roleId, targetEpoch, true)) {
                return true;
            }
        }
        for (Integer roleId : startedRoles) {
            if (isRoleBehind(roleId, targetEpoch, true)) {
                return true;
            }
        }
        for (Integer roleId : pendingRoles) {
            if (isRoleBehind(roleId, targetEpoch, false)) {
                return true;
            }
        }
        return false;
    }

    private static void failFastOnDeadPendingRole() {
        for (Map.Entry<Integer, NavigableSet<Long>> entry : pendingSeqsByRole.entrySet()) {
            NavigableSet<Long> rolePending = entry.getValue();
            if (rolePending == null || rolePending.isEmpty()) {
                continue;
            }
            Integer ownerRole = entry.getKey();
            Thread ownerThread = roleIdToThread.get(ownerRole);
            if (ownerThread != null && !ownerThread.isAlive()) {
                reportDivergence(ownerRole, "thread exited with pending replay head seq=" + rolePending.first());
                return;
            }
        }
    }

    private static boolean isRoleBehind(int roleId, long targetEpoch, boolean requireLiveThread) {
        Long roleHeadSeq = headSeq(roleId);
        if (roleHeadSeq == null) {
            return false;
        }
        long headEpoch = ReducedTraceRegistry.lookupEpoch(roleHeadSeq);
        if (headEpoch >= targetEpoch) {
            return false;
        }
        if (requireLiveThread) {
            Thread thread = roleIdToThread.get(roleId);
            if (thread != null && !thread.isAlive()) {
                reportDivergence(roleId, "thread exited with unconsumed replay events");
                return false;
            }
        }
        return true;
    }

    private static boolean isArrayScoped(int packedType) {
        int flags = (packedType >>> 8) & 0xFF;
        return (flags & (BinarySchema.Flags.IS_ARRAY_VALUED | BinarySchema.Flags.IS_ARRAY_ATOMIC)) != 0;
    }

    private static long traceLifecycleKey(long[] event) {
        int packedType = (int) event[2];
        int baseType = packedType & 0xFF;
        if (baseType == BinarySchema.Event.NONDETERMINISTIC_INT
                || baseType == BinarySchema.Event.NONDETERMINISTIC_LONG) {
            return Long.MIN_VALUE;
        }
        if (isArrayScoped(packedType)) {
            return packTraceKey(packedType >>> 16, (int) event[3]);
        }
        return packTraceKey((int) event[3], (int) event[4]);
    }

    private static long replayLocationKey(long[] event) {
        int packedType = (int) event[2];
        if (isArrayScoped(packedType)) {
            long arrayKey = packTraceKey(packedType >>> 16, (int) event[3]);
            return (arrayKey * 31L) ^ ((int) event[4] & 0xFFFFFFFFL);
        }
        return packTraceKey((int) event[3], (int) event[4]);
    }

    private static long packTraceKey(int siteId, int count) {
        return ((long) siteId << 32) | (count & 0xFFFFFFFFL);
    }

    private static boolean isWriteEvent(int baseType) {
        return baseType == BinarySchema.Event.FIELD_WRITE
                || baseType == BinarySchema.Event.ARRAY_WRITE
                || baseType == BinarySchema.Event.ATOMIC_WRITE
                || baseType == BinarySchema.Event.ATOMIC_RMW
                || baseType == BinarySchema.Event.ATOMIC_CAS;
    }

    private static boolean isTrackedEvent(int baseType) {
        return baseType == BinarySchema.Event.FIELD_READ
                || baseType == BinarySchema.Event.FIELD_WRITE
                || baseType == BinarySchema.Event.ARRAY_READ
                || baseType == BinarySchema.Event.ARRAY_WRITE
                || baseType == BinarySchema.Event.ATOMIC_READ
                || baseType == BinarySchema.Event.ATOMIC_WRITE
                || baseType == BinarySchema.Event.ATOMIC_RMW
                || baseType == BinarySchema.Event.NONDETERMINISTIC_INT
                || baseType == BinarySchema.Event.NONDETERMINISTIC_LONG;
    }

    private static long[] copyEvent(long[] event) {
        return new long[] { event[0], event[1], event[2], event[3], event[4], event[5], event[6] };
    }

    private static String pendingEventSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("pending_events=").append(pendingSeqs.size());
        builder.append(",roles=");
        boolean first = true;
        for (Map.Entry<Integer, NavigableSet<Long>> entry : pendingSeqsByRole.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(entry.getKey()).append('(').append(entry.getValue().size()).append(')');
        }
        return builder.toString();
    }

    private static void recordDegradation(String details) {
        degradedReplay = true;
        if (firstDegradationInfo.isEmpty()) {
            firstDegradationInfo = details == null ? "" : details;
        }
    }

    private static void recordValueDivergence(String details) {
        valueDivergedReplay = true;
        if (firstValueDivergenceInfo.isEmpty()) {
            firstValueDivergenceInfo = details == null ? "" : details;
        }
    }

    private static void recordUnsupported(String details) {
        unsupportedReplay = true;
        if (firstUnsupportedInfo.isEmpty()) {
            firstUnsupportedInfo = details == null ? "" : details;
        }
    }

    private static void tryPrebindStaticFieldValue(FieldKey fieldKey, long[] event, ClassLoader loader,
            Set<String> loadedClassNames) {
        if (fieldKey == null || event == null) {
            return;
        }
        try {
            String ownerName = fieldKey.owner().internalName().replace('/', '.');
            if (loadedClassNames != null && !loadedClassNames.contains(ownerName)) {
                return;
            }
            ClassLoader effectiveLoader = loader != null ? loader : Thread.currentThread().getContextClassLoader();
            if (effectiveLoader == null) {
                effectiveLoader = ClassLoader.getSystemClassLoader();
            }
            Class<?> ownerClass = Class.forName(ownerName, false, effectiveLoader);
            Field field = ownerClass.getDeclaredField(fieldKey.name());
            field.setAccessible(true);
            Object value = field.get(null);
            if (value == null) {
                return;
            }
            SemanticIdentity.bindValueIdentity((int) event[5], (int) event[6], value, semanticShapeKey(event, fieldKey));
            IdentityMapper.registerByBirthId((int) event[5], (int) event[6], value);
        } catch (Throwable ignored) {
            // Pre-binding is best-effort. Replay can still fall back to runtime binding.
        }
    }

    private static void reportDivergence(int roleId, String details) {
        if (hasDiverged) {
            return;
        }
        hasDiverged = true;
        firstDivergenceInfo = "role=" + roleId + " | " + details;
        System.err.println("[DIVERGENCE] Replay has structurally diverged: " + firstDivergenceInfo);
    }

    private static void trackFidelityInt(int packedType, int objSite, int objCount, int naturalValue, long[] event) {
        if (!fidelityEnabled) {
            return;
        }
        int baseType = packedType & 0xFF;
        if (isWriteEvent(baseType)) {
            long key = event != null ? replayLocationKey(event) : packTraceKey(objSite, objCount);
            long naturalLong = naturalValue & 0xFFFFFFFFL;
            finalStateMap.computeIfPresent(key, (ignored, current) -> {
                current[1] = naturalLong;
                return current;
            });
        }
        if (event != null && isTrackedEvent(baseType)) {
            totalValuedEvents.incrementAndGet();
            if (naturalValue == (int) event[6]) {
                naturalAgreements.incrementAndGet();
            } else if (shouldInjectReadValue(packedType)
                    || baseType == BinarySchema.Event.NONDETERMINISTIC_INT) {
                injectedEvents.incrementAndGet();
            }
        }
    }

    private static void trackFidelityLong(int packedType, int objSite, int objCount, long naturalValue, long[] event) {
        if (!fidelityEnabled) {
            return;
        }
        int baseType = packedType & 0xFF;
        if (isWriteEvent(baseType)) {
            long key = event != null ? replayLocationKey(event) : packTraceKey(objSite, objCount);
            finalStateMap.computeIfPresent(key, (ignored, current) -> {
                current[1] = naturalValue;
                return current;
            });
        }
        if (event != null && isTrackedEvent(baseType)) {
            long traceValue = ((long) event[5] << 32) | (event[6] & 0xFFFFFFFFL);
            totalValuedEvents.incrementAndGet();
            if (naturalValue == traceValue) {
                naturalAgreements.incrementAndGet();
            } else if (shouldInjectReadValue(packedType)
                    || baseType == BinarySchema.Event.NONDETERMINISTIC_LONG) {
                injectedEvents.incrementAndGet();
            }
        }
    }

    private static void trackFidelityObj(int packedType, int objSite, int objCount, Object naturalValue, long[] event) {
        if (!fidelityEnabled) {
            return;
        }
        int baseType = packedType & 0xFF;
        if (isWriteEvent(baseType)) {
            long key = event != null ? replayLocationKey(event) : packTraceKey(objSite, objCount);
            long naturalTrace = SemanticIdentity.lookupTraceId(naturalValue);
            finalStateMap.computeIfPresent(key, (ignored, current) -> {
                current[1] = naturalTrace;
                return current;
            });
        }
        if (event != null && isTrackedEvent(baseType)) {
            long traceValue = ((long) (int) event[5] << 32) | ((int) event[6] & 0xFFFFFFFFL);
            long naturalTrace = SemanticIdentity.lookupTraceId(naturalValue);
            totalValuedEvents.incrementAndGet();
            if (naturalTrace == traceValue) {
                naturalAgreements.incrementAndGet();
            } else if (shouldInjectReadValue(packedType)) {
                injectedEvents.incrementAndGet();
            }
        }
    }

    private static String semanticDetail(long[] expected) {
        SemanticObjectEvent objectEvent = SemanticTraceRegistry.lookupObjectEvent(expected[0]);
        if (objectEvent != null) {
            if (objectEvent.fieldKey() != null) {
                return " field=" + objectEvent.fieldKey();
            }
            if (objectEvent.isArray() || objectEvent.isAtomic()) {
                return " owner=" + objectEvent.ownerTypeName() + " index=" + objectEvent.index();
            }
            if (objectEvent.isThread()) {
                return " thread=" + objectEvent.ownerTypeName()
                        + " sourceSite=" + objectEvent.sourceSiteId()
                        + " targetRole=" + objectEvent.targetRoleId();
            }
            if (objectEvent.isClassInit()) {
                return " classInitSite=" + objectEvent.sourceSiteId();
            }
            if (objectEvent.isException()) {
                return " exception=" + objectEvent.ownerTypeName()
                        + " sourceSite=" + objectEvent.sourceSiteId();
            }
            if (objectEvent.isNondeterministic()) {
                return " nondetSite=" + objectEvent.sourceSiteId();
            }
            if (objectEvent.isSync()) {
                return " sync=" + objectEvent.ownerTypeName()
                        + " sourceSite=" + objectEvent.sourceSiteId();
            }
        }
        FieldInteractionDomain domain = ReducedTraceRegistry.lookupFieldDomain(expected[0]);
        if (domain != null) {
            return " domain=" + domain.domainId();
        }
        String domainId = ReducedTraceRegistry.lookupDomainId(expected[0]);
        if (domainId != null && !domainId.isEmpty()) {
            return " domain=" + domainId;
        }
        return "";
    }

    private static String semanticShapeKey(long[] expected, FieldKey actualFieldKey) {
        String domainId = ReducedTraceRegistry.lookupDomainId(expected[0]);
        String traceShape = SemanticTraceRegistry.semanticShapeKey(expected[0], domainId);
        if (!traceShape.isEmpty()) {
            return traceShape;
        }
        StringBuilder builder = new StringBuilder();
        builder.append((int) expected[2] & 0xFF);
        if (domainId != null && !domainId.isEmpty()) {
            builder.append('|').append(domainId);
        }
        SemanticObjectEvent objectEvent = SemanticTraceRegistry.lookupObjectEvent(expected[0]);
        if (objectEvent != null) {
            if (objectEvent.fieldKey() != null) {
                builder.append('|').append(objectEvent.fieldKey());
            }
            if (!objectEvent.ownerTypeName().isEmpty()) {
                builder.append('|').append(objectEvent.ownerTypeName());
            }
            if (objectEvent.index() >= 0) {
                builder.append('|').append(objectEvent.index());
            }
            if (objectEvent.sourceSiteId() >= 0) {
                builder.append('|').append("site=").append(objectEvent.sourceSiteId());
            }
            if (objectEvent.targetRoleId() >= 0) {
                builder.append('|').append("targetRole=").append(objectEvent.targetRoleId());
            }
        } else if (actualFieldKey != null) {
            builder.append('|').append(actualFieldKey);
        }
        return builder.toString();
    }

    private static void debug(String message) {
        if (Boolean.getBoolean("tool.trace.debug")) {
            System.err.println(message);
        }
    }
}
