package replay;

import common.BinarySchema;
import common.IdentityMapper;
import common.ReplayBoundaryRegistry;
import common.ReplayBoundaryRegistry.BoundaryMeta;
import common.TraceSemantics;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ReplayCoordinator {
    // Pre-sorted boundary event array: each element is [seq, roleId, packedType,
    // objSite, objCount, data1, data2].
    private static long[][] sortedEvents;
    private static long totalEvents;
    private static final AtomicLong currentIdx = new AtomicLong(0);
    private static final Object controlLock = new Object();
    private static final AtomicLong releasedEpoch = new AtomicLong(0);

    // Stores the seq of the last matched event for the calling thread,
    // so ReplayMonitor can print epoch/seq after awaitTurn returns.
    private static final ThreadLocal<Long> lastMatchedSeq = ThreadLocal.withInitial(() -> 0L);

    // Universal target epoch: the lowest epoch any role is currently trying to
    // advance releasedEpoch to. Roles with release events at higher epochs must
    // defer until the pending lower-epoch release succeeds, preventing circular
    // waits (e.g. Role A waiting for Role B at epoch N while Role B is waiting
    // for Role A at epoch N+1).
    private static final AtomicLong pendingTargetEpoch = new AtomicLong(Long.MAX_VALUE);

    public static long getLastMatchedSeq() { return lastMatchedSeq.get(); }

    // Roles seen in the trace that haven't checked in yet
    private static final Set<Integer> pendingRoles = ConcurrentHashMap.newKeySet();
    // Roles that have called awaitTurn at least once — they're alive in replay
    private static final Set<Integer> activeRoles = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> startedRoles = ConcurrentHashMap.newKeySet();

    // Map each role to its own sequence of events
    private static final Map<Integer, LinkedList<long[]>> roleQueues = new ConcurrentHashMap<>();
    private static final AtomicLong eventsMatched = new AtomicLong(0);
    private static final AtomicLong globalMatchCount = new AtomicLong(0);
    
    // Maps roleId → the Thread object that owns it, so isAnyRoleBehind can skip
    // roles whose thread has exited normally (without an uncaught exception).
    private static final ConcurrentHashMap<Integer, Thread> roleIdToThread = new ConcurrentHashMap<>();

    // Global divergence flag. Set at most once; once true all awaitTurn* methods
    // return immediately so threads run freely rather than deadlocking.
    private static volatile boolean hasDiverged = false;
    private static volatile String firstDivergenceInfo = null;
    private static volatile boolean isIncomplete = false;
    private static volatile String incompleteReason = "";
    private static volatile String incompleteDetails = "";
    private static final ArrayList<ScheduleDistiller.ScheduleEntry> scheduleEntries = new ArrayList<>();
    private static final AtomicLong currentScheduleIdx = new AtomicLong(0);

    public static boolean hasDiverged() { return hasDiverged; }

    // ---- Fidelity tracking (opt-in, zero cost when disabled) ----
    // Activated by -Dtool.fidelity.output=<path>; set by ReplayAgent.premain().
    static volatile boolean fidelityEnabled    = false;
    static volatile String  fidelityOutputPath = null;

    // Counts all valued events (reads + writes) where we have a trace event to compare.
    private static final AtomicLong totalValuedEvents = new AtomicLong(0);
    private static final AtomicLong naturalAgreements = new AtomicLong(0);
    // Counts valued events where the natural value differed from the trace.
    private static final AtomicLong naturalDisagreements = new AtomicLong(0);

    // key  = (objSite << 32) | (objCount & 0xFFFFFFFFL)
    // v[0] = captured final value (packed data1<<32|data2)
    // v[1] = last natural value seen during replay (Long.MIN_VALUE = never written)
    private static final ConcurrentHashMap<Long, long[]> finalStateMap = new ConcurrentHashMap<>();

    public static void init(MappedByteBuffer traceBuffer, long count) {
        // Clear all state so init() is safe to call more than once (e.g. in tests).
        sortedEvents = null;
        totalEvents = 0;
        currentIdx.set(0);
        releasedEpoch.set(0);
        pendingTargetEpoch.set(Long.MAX_VALUE);
        pendingRoles.clear();
        activeRoles.clear();
        startedRoles.clear();
        roleQueues.clear();
        globalMatchCount.set(0);
        roleIdToThread.clear();
        hasDiverged = false;
        firstDivergenceInfo = null;
        isIncomplete = false;
        incompleteReason = "";
        incompleteDetails = "";
        currentScheduleIdx.set(0);
        eventsMatched.set(0);
        totalValuedEvents.set(0);
        naturalAgreements.set(0);
        naturalDisagreements.set(0);
        finalStateMap.clear();

        ArrayList<long[]> allEvents = new ArrayList<>();
        ArrayList<long[]> boundaryEvents = new ArrayList<>();
        long maxSlots = traceBuffer.capacity() / BinarySchema.RECORD_SIZE;

        for (long i = 0; i < maxSlots; i++) {
            int pos = (int) (i * BinarySchema.RECORD_SIZE);
            long seq = traceBuffer.getLong(pos);
            if (seq == 0) continue; // Quick skip

            long[] event = new long[] {
                seq,                               // [0] Full Seq (Epoch | Local)
                traceBuffer.getLong(pos + 8),      // [1] roleId
                traceBuffer.getInt(pos + 16),      // [2] packedType
                traceBuffer.getInt(pos + 20),      // [3] objSite
                traceBuffer.getInt(pos + 24),      // [4] objCount
                traceBuffer.getInt(pos + 28),      // [5] data1
                traceBuffer.getInt(pos + 32)       // [6] data2
            };
            allEvents.add(event);
            if (TraceSemantics.isReplayBoundary((int) event[2])) {
                boundaryEvents.add(event);
            }
        }

        // Sort globally once to maintain the captured causal order.
        allEvents.sort(Comparator.comparingLong(a -> a[0]));
        boundaryEvents.sort(Comparator.comparingLong(a -> a[0]));
        sortedEvents = boundaryEvents.toArray(new long[0][]);

        // Distribute replay boundaries into per-role queues. Schedule replay no longer
        // coordinates non-boundary events.
        for (long[] event : boundaryEvents) {
            int rId = (int) event[1];
            roleQueues.computeIfAbsent(rId, k -> new LinkedList<>()).add(event);
            pendingRoles.add(rId); 
        }

        totalEvents = boundaryEvents.size();
        // System.out.println("[Replay] Distributed " + totalEvents + " events into " + roleQueues.keySet().size() + " role queues.");

        // Pre-compute captured final state for fidelity tracking.
        // Iterating in causal order means the last write per location wins.
        if (fidelityEnabled) {
            for (long[] ev : allEvents) {
                if (!isWriteEvent((int) ev[2] & 0xFF)) continue;
                long key = ((long)(int) ev[3] << 32) | ((int) ev[4] & 0xFFFFFFFFL);
                long val = ((long)(int) ev[5] << 32) | ((int) ev[6] & 0xFFFFFFFFL);
                finalStateMap.compute(key, (k, v) ->
                    v == null ? new long[]{val, Long.MIN_VALUE} : new long[]{val, v[1]});
            }
        }
    }

    public static void loadScheduleArtifact(List<ScheduleDistiller.ScheduleEntry> entries) {
        synchronized (controlLock) {
            scheduleEntries.clear();
            scheduleEntries.addAll(entries);
            scheduleEntries.sort(Comparator.comparingLong(e -> e.seq));
            currentScheduleIdx.set(0);
        }
    }
    /**
     * Records the first structural divergence and wakes all waiting threads so
     * they can exit their spin loops and run without synchronization guidance.
     * Uses double-checked locking so only the very first divergence is recorded.
     */
    private static void reportDivergence(int roleId, long epoch,
            int expectedType, int actualType, String extra) {
        if (hasDiverged) return;
        synchronized (controlLock) {
            if (hasDiverged) return;
            hasDiverged = true;
            firstDivergenceInfo = String.format(
                    "role=%d epoch=%d expectedType=0x%02x actualType=0x%02x | %s",
                    roleId, epoch, expectedType & 0xFF, actualType & 0xFF, extra);
            System.err.println("[DIVERGENCE] Replay has structurally diverged: " + firstDivergenceInfo);
            System.err.println("[DIVERGENCE] Synchronization guidance will no longer be applied.");
            controlLock.notifyAll();
        }
    }

    /** Overload for divergences that have no expected/actual event type (e.g. early thread exit). */
    private static void reportDivergence(int roleId, String extra) {
        if (hasDiverged) return;
        synchronized (controlLock) {
            if (hasDiverged) return;
            hasDiverged = true;
            firstDivergenceInfo = "role=" + roleId + " | " + extra;
            System.err.println("[DIVERGENCE] Replay has structurally diverged: " + firstDivergenceInfo);
            System.err.println("[DIVERGENCE] Synchronization guidance will no longer be applied.");
            controlLock.notifyAll();
        }
    }

    /**
     * Registers the main thread so its role is immediately active.
     * The main thread is always the first role in the sorted trace.
     * Without this, the main thread's early events (CLASS_INIT, etc.) would be
     * treated as deadlocked because its role stays in pendingRoles until checkIn
     * is called — but checkIn is only called for newly spawned threads.
     */
    public static void registerMainThread(long mainTid) {
        if (sortedEvents == null || sortedEvents.length == 0) return;
        int mainRole = (int) sortedEvents[0][1];
        pendingRoles.remove(mainRole);
        activeRoles.add(mainRole);
        roleIdToThread.put(mainRole, Thread.currentThread());
        IdentityMapper.preAssignRole(mainTid, mainRole);
        // System.out.println("[Replay] main thread registered as role=" + mainRole);
    }

    public static int peekNextPendingRole() {
        synchronized (controlLock) {
            int bestRole = -1;
            long lowestSeq = Long.MAX_VALUE;

            // Iterate through all queues to find the 'next' logical thread to start
            for (Map.Entry<Integer, LinkedList<long[]>> entry : roleQueues.entrySet()) {
                int roleId = entry.getKey();

                // Only consider roles that are truly PENDING (not started, not active)
                if (pendingRoles.contains(roleId) && !startedRoles.contains(roleId) && !activeRoles.contains(roleId)) {
                    LinkedList<long[]> queue = entry.getValue();
                    if (queue != null && !queue.isEmpty()) {
                        long headSeq = queue.peek()[0];

                        // We want the role that appears EARLIEST in the global timeline
                        if (headSeq < lowestSeq) {
                            lowestSeq = headSeq;
                            bestRole = roleId;
                        }
                    }
                }
            }
            return bestRole;
        }
    }

    /**
     * Scans the parent role's event queue for its next unconsumed THREAD_START
     * event and returns the child roleId stored in data1 (field [5]).
     * During capture, TraceLogger.logSync writes childRoleId into data1 for
     * every THREAD_START record, so this lookup is authoritative.
     * Returns -1 if the parent queue has no pending THREAD_START event.
     */
    public static int peekChildRoleFromThreadStart(int parentRoleId) {
        synchronized (controlLock) {
            LinkedList<long[]> parentQueue = roleQueues.get(parentRoleId);
            if (parentQueue == null) return -1;
            for (long[] event : parentQueue) {
                int eType = (int) event[2] & 0xFF;
                if (eType == BinarySchema.Event.THREAD_START) {
                    return (int) event[5]; // data1 = childRoleId written at capture time
                }
            }
            return -1;
        }
    }

    // In ReplayCoordinator — called from IdentityMapper on first role assignment
    public static void checkIn(int roleId) {
        if (pendingRoles.remove(roleId)) {
            startedRoles.add(roleId);
            roleIdToThread.put(roleId, Thread.currentThread());
            synchronized (controlLock) {
                // System.out.println("[Replay] role=" + roleId + " started, waiting to be scheduled.");
                controlLock.notifyAll();
            }
        }
    }

    /** Called by the UncaughtExceptionHandler when a replay thread dies early. */
    public static void reportThreadDead(int roleId) {
        LinkedList<long[]> queue = roleQueues.get(roleId);
        if (queue != null && !queue.isEmpty()) {
            reportDivergence(roleId, "thread exited with " + queue.size() + " unconsumed events");
        }
        activeRoles.remove(roleId);
        startedRoles.remove(roleId);
        pendingRoles.remove(roleId);
        synchronized (controlLock) {
            controlLock.notifyAll();
        }
    }

    private static void activateRole(int roleId) {
        if (activeRoles.contains(roleId))
            return;
        // System.out.println("[Replay] Activating role=" + roleId);
        synchronized (controlLock) {
            // Re-check after acquiring lock to prevent double-activation
            if (activeRoles.contains(roleId)) return;
            // Move the role to active regardless of its current state (pending or started)
            boolean removed = pendingRoles.remove(roleId) || startedRoles.remove(roleId);
            activeRoles.add(roleId);
            // Store the actual worker thread so that isAnyRoleBehind's t.isAlive()
            // check reflects when THIS thread exits, not an earlier caller.
            roleIdToThread.put(roleId, Thread.currentThread());
            // System.out.println("[Replay] role=" + roleId + " is now active.");
            controlLock.notifyAll();
        }
    }

    /**
     * Legacy strict-replay path retained for compatibility with older call sites.
     * Schedule replay primarily coordinates through {@link #awaitScheduleTurn}.
     *
     * Parameters mirror the fields written by BinarySchema.write() during capture
     * (minus seq, which is only used for ordering):
     *
     * @param roleId     Resolved role ID for the current thread
     * @param packedType Event type + flags (same packing as BinarySchema)
     * @param objSite    Birth site of the target object (lock, field owner, array)
     * @param objCount   Birth count of the target object
     * @param data       Event-specific payload (siteId for sync, fieldId for
     *                   fields,
     *                   array index for arrays, return value / siteId for atomics)
     */
    public static void awaitTurn(int roleId, int packedType, int objSite, int objCount, int data) {
        activateRole(roleId);
        LinkedList<long[]> myQueue = roleQueues.get(roleId);
        if (myQueue == null) return;

        synchronized (controlLock) {
            while (true) {
                if (hasDiverged) { controlLock.notifyAll(); return; }

                long[] expected = myQueue.peek();
                if (expected == null) return;

                long eSeq = expected[0];
                long eEpoch = eSeq >>> 32;
                int eType = (int) expected[2];

                // --- EPOCH GUARD ---
                // Only release events may advance releasedEpoch (they are the synchronisation
                // boundary). Non-release events (MONITOR_ENTER, field reads, etc.) just wait
                // until the release event that opened this epoch has been processed.
                if (eEpoch > releasedEpoch.get()) {
                    if (isReleaseEvent(eType)) {
                        // Register our target and get the current global minimum.
                        // Only the role with the lowest pending target epoch may advance;
                        // roles targeting higher epochs defer so lower-epoch releases go
                        // first, breaking circular waits.
                        long lowestPending = pendingTargetEpoch.accumulateAndGet(eEpoch, Math::min);
                        if (lowestPending < eEpoch) {
                            // An earlier epoch is pending — defer and wait for it to advance.
                            try {
                                controlLock.wait(100);
                                continue;
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        if (!isAnyRoleBehind(eEpoch)) {
                            // This release event is the epoch leader — open the new epoch.
                            // System.out.println("[Replay] Role " + roleId + " advancing Epoch to " + eEpoch + " for type " + (eType & 0xFF));
                            releasedEpoch.set(eEpoch);
                            pendingTargetEpoch.compareAndSet(eEpoch, Long.MAX_VALUE);
                        } else {
                            try {
                                controlLock.wait(100);
                                continue;
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    } else {
                        try {
                            controlLock.wait(100);
                            continue;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }

                // --- THE MATCH CHECK ---
                if (packedType == eType && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    myQueue.poll();
                    if (fidelityEnabled) eventsMatched.incrementAndGet();
                    lastMatchedSeq.set(eSeq);
                    // Standard JMM release check (redundant now, but good for safety)
                    if (isReleaseEvent(eType)) {
                        releasedEpoch.set(eEpoch);
                    }
                    
                    controlLock.notifyAll(); // Wake up anyone waiting for this epoch/seq
                    return;
                }

                // --- DIVERGENCE: structural mismatch — stop all synchronization ---
                myQueue.poll();
                lastMatchedSeq.set(eSeq);
                if (isReleaseEvent(eType)) {
                    releasedEpoch.set(eEpoch);
                    pendingTargetEpoch.compareAndSet(eEpoch, Long.MAX_VALUE);
                }
                reportDivergence(roleId, eEpoch, eType, packedType,
                        "sync event mismatch: objSite=" + objSite + " objCount=" + objCount);
                return;
            }
        }
    }

    public static void awaitScheduleTurn(int roleId, int packedType, int rawSiteId) {
        activateRole(roleId);
        int eventType = packedType & 0xFF;
        BoundaryMeta actual = ReplayBoundaryRegistry.get(rawSiteId);

        synchronized (controlLock) {
            while (true) {
                if (hasDiverged) { controlLock.notifyAll(); return; }

                long idx = currentScheduleIdx.get();
                if (idx >= scheduleEntries.size()) {
                    controlLock.notifyAll();
                    return;
                }

                ScheduleDistiller.ScheduleEntry expected = scheduleEntries.get((int) idx);

                if (expected.roleId != roleId) {
                    try {
                        controlLock.wait(100);
                        continue;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (actual == null) {
                    reportDivergence(roleId, expected.epoch, expected.eventType, eventType,
                            "missing replay boundary metadata for rawSiteId=" + rawSiteId);
                    return;
                }

                if (expected.eventType == eventType
                        && expected.className.equals(actual.className)
                        && expected.methodName.equals(actual.methodName)) {
                    currentScheduleIdx.incrementAndGet();
                    lastMatchedSeq.set(expected.seq);
                    consumeStrictBoundaryHead(roleId, expected.seq, packedType);
                    controlLock.notifyAll();
                    return;
                }

                reportDivergence(roleId, expected.epoch, expected.eventType, eventType,
                        "schedule boundary mismatch: expected="
                                + expected.className + "." + expected.methodName
                                + " actual=" + actual.className + "." + actual.methodName
                                + " rawSiteId=" + rawSiteId);
                return;
            }
        }
    }

    private static void consumeStrictBoundaryHead(int roleId, long expectedSeq, int packedType) {
        LinkedList<long[]> myQueue = roleQueues.get(roleId);
        if (myQueue == null) return;
        long[] head = myQueue.peek();
        if (head == null) return;
        if (head[0] != expectedSeq) return;

        myQueue.poll();
        if (fidelityEnabled) eventsMatched.incrementAndGet();

        int headPackedType = (int) head[2];
        long epoch = expectedSeq >>> 32;
        if (isReleaseEvent(headPackedType)) {
            releasedEpoch.set(epoch);
            pendingTargetEpoch.compareAndSet(epoch, Long.MAX_VALUE);
        }
    }

    /** Compatibility overload for non-boundary legacy paths. */
    public static int awaitTurnInt(int roleId, int packedType, int objSite, int objCount) {
        return awaitTurnInt(roleId, packedType, objSite, objCount, 0);
    }

    public static int awaitTurnInt(int roleId, int packedType, int objSite, int objCount, int naturalValue) {
        activateRole(roleId);
        LinkedList<long[]> myQueue = roleQueues.get(roleId);
        if (myQueue == null) return naturalValue;

        synchronized (controlLock) {
            while (true) {
                long[] expected = myQueue.peek();
                if (expected == null) return naturalValue;

                long eSeq = expected[0];
                long eEpoch = eSeq >>> 32;
                int eType = (int) expected[2];

                // Atomic reads are non-release: wait for the epoch but never advance it.
                if (eEpoch > releasedEpoch.get()) {
                    try { controlLock.wait(100); continue; }
                    catch (InterruptedException e) { return naturalValue; }
                }

                if (packedType == (int) expected[2] && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    myQueue.poll();
                    if (fidelityEnabled) eventsMatched.incrementAndGet();
                    lastMatchedSeq.set(eSeq);
                    if (isReleaseEvent(packedType)) releasedEpoch.set(eEpoch);
                    controlLock.notifyAll();
                    trackFidelityInt(packedType, objSite, objCount, naturalValue, expected);
                    return naturalValue;
                }
                myQueue.poll();
                lastMatchedSeq.set(eSeq);
                if (isReleaseEvent(eType)) releasedEpoch.set(eEpoch);
                reportDivergence(roleId, eEpoch, eType, packedType,
                        "awaitTurnInt mismatch: objSite=" + objSite + " objCount=" + objCount);
                controlLock.notifyAll();
                return naturalValue;
            }
        }
    }

    /** Compatibility overload for non-boundary legacy paths. */
    public static long awaitTurnLong(int roleId, int packedType, int objSite, int objCount) {
        return awaitTurnLong(roleId, packedType, objSite, objCount, 0L);
    }

    public static long awaitTurnLong(int roleId, int packedType, int objSite, int objCount, long naturalValue) {
        activateRole(roleId);
        LinkedList<long[]> myQueue = roleQueues.get(roleId);
        if (myQueue == null) return naturalValue;

        synchronized (controlLock) {
            while (true) {
                long[] expected = myQueue.peek();
                if (expected == null) return naturalValue;

                long eSeq = expected[0];
                long eEpoch = eSeq >>> 32;
                int eType = (int) expected[2];

                if (eEpoch > releasedEpoch.get()) {
                    try { controlLock.wait(100); continue; }
                    catch (InterruptedException e) { return naturalValue; }
                }

                if (packedType == eType && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    myQueue.poll();
                    if (fidelityEnabled) eventsMatched.incrementAndGet();
                    lastMatchedSeq.set(eSeq);
                    if (isReleaseEvent(packedType)) releasedEpoch.set(eEpoch);
                    controlLock.notifyAll();
                    trackFidelityLong(packedType, objSite, objCount, naturalValue, expected);
                    return naturalValue;
                }
                myQueue.poll();
                lastMatchedSeq.set(eSeq);
                if (isReleaseEvent(eType)) releasedEpoch.set(eEpoch);
                reportDivergence(roleId, eEpoch, eType, packedType,
                        "awaitTurnLong mismatch: objSite=" + objSite + " objCount=" + objCount);
                controlLock.notifyAll();
                return naturalValue;
            }
        }
    }

    public static Object awaitTurnObj(int roleId, int packedType, int objSite, int objCount, Object naturalValue) {
        activateRole(roleId);
        LinkedList<long[]> myQueue = roleQueues.get(roleId);
        if (myQueue == null) return naturalValue;

        synchronized (controlLock) {
            while (true) {
                long[] expected = myQueue.peek();
                if (expected == null) return naturalValue;

                long eSeq = expected[0];
                long eEpoch = eSeq >>> 32;
                int eType = (int) expected[2];

                if (eEpoch > releasedEpoch.get()) {
                    try { controlLock.wait(100); continue; }
                    catch (InterruptedException e) { return naturalValue; }
                }

                if (packedType == eType && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    myQueue.poll();
                    if (fidelityEnabled) eventsMatched.incrementAndGet();
                    lastMatchedSeq.set(eSeq);
                    if (isReleaseEvent(packedType)) releasedEpoch.set(eEpoch);
                    controlLock.notifyAll();
                    return naturalValue;
                }
                myQueue.poll();
                lastMatchedSeq.set(eSeq);
                if (isReleaseEvent(eType)) releasedEpoch.set(eEpoch);
                reportDivergence(roleId, eEpoch, eType, packedType,
                        "awaitTurnObj mismatch: objSite=" + objSite + " objCount=" + objCount);
                controlLock.notifyAll();
                return naturalValue;
            }
        }
    }

    // ---- CAS-valued helpers ----
    // Retained only for compatibility with older call paths. Schedule replay does
    // not alter natural values.

    public static int awaitTurnCasInt(int roleId, int packedType, int objSite, int objCount) {
        doAwaitTurnValued(roleId, packedType, objSite, objCount);
        return 0;
    }

    public static long awaitTurnCasLong(int roleId, int packedType, int objSite, int objCount) {
        doAwaitTurnValued(roleId, packedType, objSite, objCount);
        return 0L;
    }

    public static Object awaitTurnCasObj(int roleId, int packedType, int objSite, int objCount) {
        doAwaitTurnValued(roleId, packedType, objSite, objCount);
        return null;
    }

    // ---- RMW divergence-check methods ----
    // The atomic operation has already executed; natural value is returned regardless
    // of divergence so the program continues on its actual execution path.

    public static int awaitTurnRmwInt(int roleId, int packedType, int objSite, int objCount, int naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        trackFidelityInt(packedType, objSite, objCount, naturalValue, ev);
        if (ev == null) return naturalValue;
        int traceValue = (int) ev[6];
        if (naturalValue != traceValue) {
            if (fidelityEnabled) naturalDisagreements.incrementAndGet();
            reportDivergence(roleId, ev[0] >>> 32, (int) ev[2], packedType,
                    "RMW value mismatch: natural=" + naturalValue + " trace=" + traceValue);
        }
        return naturalValue;
    }

    public static long awaitTurnRmwLong(int roleId, int packedType, int objSite, int objCount, long naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        trackFidelityLong(packedType, objSite, objCount, naturalValue, ev);
        if (ev == null) return naturalValue;
        long traceValue = ((long) ev[5] << 32) | (ev[6] & 0xFFFFFFFFL);
        if (naturalValue != traceValue) {
            if (fidelityEnabled) naturalDisagreements.incrementAndGet();
            reportDivergence(roleId, ev[0] >>> 32, (int) ev[2], packedType,
                    "RMW value mismatch: natural=" + naturalValue + " trace=" + traceValue);
        }
        return naturalValue;
    }

    public static Object awaitTurnRmwObj(int roleId, int packedType, int objSite, int objCount, Object naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        trackFidelityObj(packedType, objSite, objCount, naturalValue, ev);
        if (ev == null) return naturalValue;
        Object traceValue = IdentityMapper.resolveByBirthId((int) ev[5], (int) ev[6]);
        if (naturalValue != traceValue) {
            if (fidelityEnabled) naturalDisagreements.incrementAndGet();
            reportDivergence(roleId, ev[0] >>> 32, (int) ev[2], packedType,
                    "RMW value mismatch: natural=" + naturalValue + " trace=" + traceValue);
        }
        return naturalValue;
    }

    public static void recordNaturalDisagreement() {
        if (!fidelityEnabled) return;
        naturalDisagreements.incrementAndGet();
    }

    // ---- Value-returning field turn methods ----
    // Same wait-loop structure as awaitTurnInt/Long/Obj but include the pending/
    // started-role deadlock detection that plain awaitTurn provides, since field
    // accesses can be the first event a newly-started thread encounters.

    public static long[] doAwaitTurnValued(int roleId, int packedType, int objSite, int objCount) {
        activateRole(roleId);
        LinkedList<long[]> myQueue = roleQueues.get(roleId);
        if (myQueue == null || myQueue.isEmpty()) return null;

        synchronized (controlLock) {
            while (true) {
                if (hasDiverged) { controlLock.notifyAll(); return null; }

                long[] expected = myQueue.peek();
                long eSeq = expected[0];
                long eEpoch = eSeq >>> 32;
                int eType = (int) expected[2];

                // // System.out.println(String.format("[Replay] Role %d awaiting event Type %d ObjSite %d ObjCount %d at Epoch %d (releasedEpoch=%d) but got event Type %d ObjSite %d ObjCount %d",
                //         roleId, eType, (int) expected[3], (int) expected[4], eEpoch, releasedEpoch.get(), packedType, objSite, objCount));
                // int eFlags = (eType >> 8) & 0xFF;
                // // System.out.println(String.format("Flags: Volatile=%b Static=%b ArrayValued=%b Release=%b",
                //         (eFlags & BinarySchema.Flags.IS_VOLATILE) != 0,
                //         (eFlags & BinarySchema.Flags.IS_STATIC) != 0,
                //         (eFlags & BinarySchema.Flags.IS_ARRAY_VALUED) != 0,
                        // isReleaseEvent(eType)));

                // --- EPOCH GUARD ---
                // Only release events may advance releasedEpoch. Field/array reads and
                // writes follow thread-local sequence without cross-thread epoch blocking;
                // they wait here only until the release event that opened this epoch fires.
                // Use > (same as awaitTurn) so events multiple epochs ahead don't bypass
                // the guard and execute out of order.
                if (eEpoch > releasedEpoch.get()) {
                    if (isReleaseEvent(eType)) {
                        // Same pendingTargetEpoch logic as awaitTurn: only the role
                        // targeting the lowest pending epoch may advance.
                        long lowestPending = pendingTargetEpoch.accumulateAndGet(eEpoch, Math::min);
                        if (lowestPending < eEpoch) {
                            try {
                                controlLock.wait(100);
                                continue;
                            } catch (InterruptedException e) { return null; }
                        }
                        if (!isAnyRoleBehind(eEpoch)) {
                            // System.out.println("[Replay] Role " + roleId + " advancing global epoch to " + eEpoch);
                            releasedEpoch.set(eEpoch);
                            pendingTargetEpoch.compareAndSet(eEpoch, Long.MAX_VALUE);
                        } else {
                            try {
                                controlLock.wait(100);
                                continue;
                            } catch (InterruptedException e) { return null; }
                        }
                    } else {
                        try {
                            controlLock.wait(100);
                            continue;
                        } catch (InterruptedException e) { return null; }
                    }
                }

                // --- THE MATCH ---
                if (packedType == eType && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    // // System.out.println("Match found for Role " + roleId + " at Epoch " + eEpoch + " with Type " + (eType & 0xFF));
                    myQueue.poll();
                    if (fidelityEnabled) eventsMatched.incrementAndGet();
                    lastMatchedSeq.set(eSeq);
                    
                    // If this specific event was a release, make sure we update (redundancy)
                    if (isReleaseEvent((int)expected[2])) {
                        releasedEpoch.set(eEpoch);
                    }
                    
                    controlLock.notifyAll();
                    return expected;
                }

                // --- DIVERGENCE: type/identity mismatch ---
                myQueue.poll();
                lastMatchedSeq.set(eSeq);
                if (isReleaseEvent((int) expected[2])) {
                    releasedEpoch.set(eEpoch);
                    pendingTargetEpoch.compareAndSet(eEpoch, Long.MAX_VALUE);
                }
                controlLock.notifyAll();
                reportDivergence(roleId, eEpoch, (int) expected[2], packedType,
                        "valued event mismatch: expected objSite=" + (int) expected[3]
                        + " objCount=" + (int) expected[4]
                        + " got objSite=" + objSite + " objCount=" + objCount);
                return null;
            }
        }
    }

    public static int awaitTurnFieldInt(int roleId, int packedType, int objSite, int objCount, int naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        trackFidelityInt(packedType, objSite, objCount, naturalValue, ev);
        return naturalValue;
    }

    public static long awaitTurnFieldLong(int roleId, int packedType, int objSite, int objCount, long naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        trackFidelityLong(packedType, objSite, objCount, naturalValue, ev);
        return naturalValue;
    }

    public static Object awaitTurnFieldObj(int roleId, int packedType, int objSite, int objCount, Object naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        trackFidelityObj(packedType, objSite, objCount, naturalValue, ev);
        return naturalValue;
    }

    // ---- Value-returning array turn methods ----
    // Array records use the IS_ARRAY_VALUED packing:
    //   packedType upper 16 bits = birthId.siteId
    //   objSite = birthId.count,  objCount = index

    public static int awaitTurnArrayInt(int roleId, int packedType, int objSite, int objCount, int naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        // For arrays: objSite=array-birthId.count, objCount=element-index — key is per element.
        trackFidelityInt(packedType, objSite, objCount, naturalValue, ev);
        return naturalValue;
    }

    public static long awaitTurnArrayLong(int roleId, int packedType, int objSite, int objCount, long naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        trackFidelityLong(packedType, objSite, objCount, naturalValue, ev);
        return naturalValue;
    }

    public static Object awaitTurnArrayObj(int roleId, int packedType, int objSite, int objCount, Object naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        trackFidelityObj(packedType, objSite, objCount, naturalValue, ev);
        return naturalValue;
    }

    private static boolean isWriteEvent(int baseType) {
        return baseType == BinarySchema.Event.FIELD_WRITE
            || baseType == BinarySchema.Event.ARRAY_WRITE
            || baseType == BinarySchema.Event.ATOMIC_WRITE
            || baseType == BinarySchema.Event.ATOMIC_RMW
            || baseType == BinarySchema.Event.ATOMIC_CAS;
    }

    /** True for all valued memory-access events (reads + writes). Excludes nondeterministic. */
    private static boolean isTrackedEvent(int baseType) {
        return baseType == BinarySchema.Event.FIELD_READ
            || baseType == BinarySchema.Event.FIELD_WRITE
            || baseType == BinarySchema.Event.ARRAY_READ
            || baseType == BinarySchema.Event.ARRAY_WRITE
            || baseType == BinarySchema.Event.ATOMIC_READ
            || baseType == BinarySchema.Event.ATOMIC_WRITE
            || baseType == BinarySchema.Event.ATOMIC_RMW;
    }

    /**
     * Records fidelity stats for one int-sized valued event.
     *
     * <p>The finalStateMap update is done unconditionally (ev may be null when the
     * run has diverged, but the last natural write still determines final state).
     * Counter increments are only done when ev is non-null, i.e. we have a matched
     * trace event to compare against.
     */
    private static void trackFidelityInt(int packedType, int objSite, int objCount,
                                         int naturalValue, long[] ev) {
        if (!fidelityEnabled) return;
        int baseType = packedType & 0xFF;
        if (isWriteEvent(baseType)) {
            long key    = ((long) objSite << 32) | (objCount & 0xFFFFFFFFL);
            long natLong = naturalValue & 0xFFFFFFFFL;
            finalStateMap.computeIfPresent(key, (k, v) -> { v[1] = natLong; return v; });
        }
        if (ev != null && isTrackedEvent(baseType)) {
            totalValuedEvents.incrementAndGet();
            if (naturalValue == (int) ev[6]) naturalAgreements.incrementAndGet();
            else if (baseType != BinarySchema.Event.ATOMIC_RMW) naturalDisagreements.incrementAndGet();
        }
    }

    /** Same as {@link #trackFidelityInt} but for long-sized valued events. */
    private static void trackFidelityLong(int packedType, int objSite, int objCount,
                                          long naturalValue, long[] ev) {
        if (!fidelityEnabled) return;
        int baseType = packedType & 0xFF;
        if (isWriteEvent(baseType)) {
            long key = ((long) objSite << 32) | (objCount & 0xFFFFFFFFL);
            finalStateMap.computeIfPresent(key, (k, v) -> { v[1] = naturalValue; return v; });
        }
        if (ev != null && isTrackedEvent(baseType)) {
            long traceValue = ((long) ev[5] << 32) | (ev[6] & 0xFFFFFFFFL);
            totalValuedEvents.incrementAndGet();
            if (naturalValue == traceValue) naturalAgreements.incrementAndGet();
            else if (baseType != BinarySchema.Event.ATOMIC_RMW) naturalDisagreements.incrementAndGet();
        }
    }

    /**
     * Records fidelity stats for one object-reference valued event.
     *
     * <p>For write events the location's entry in finalStateMap is updated with the
     * natural object's birth ID packed as {@code (siteId << 32) | count}, matching
     * the format used by {@link #init()} when it pre-computes the captured final
     * state.  The captured birth ID is read directly from {@code ev[5..6]}.
     *
     * <p>This method must be called <em>after</em> any {@code registerByBirthId}
     * call (the {@code traceValue == null} branch) so that
     * {@link IdentityMapper#lookupBirthId} returns the updated identity.
     *
     * <p>{@code ev} may be null when the run has already diverged; in that case
     * only the finalStateMap write update (if applicable) is performed.
     */
    private static void trackFidelityObj(int packedType, int objSite, int objCount,
                                          Object naturalValue, long[] ev) {
        if (!fidelityEnabled) return;
        int baseType = packedType & 0xFF;
        int flags = (packedType >>> 8) & 0xFF;
        boolean isArrayValued = (flags & BinarySchema.Flags.IS_ARRAY_VALUED) != 0;
        if (isWriteEvent(baseType)) {
            IdentityMapper.BirthId nat = IdentityMapper.lookupBirthId(naturalValue);
            long natLong = nat != null
                    ? ((long) nat.siteId << 32) | (nat.count & 0xFFFFFFFFL)
                    : Long.MIN_VALUE;
            long key = ((long) objSite << 32) | (objCount & 0xFFFFFFFFL);
            finalStateMap.computeIfPresent(key, (k, v) -> { v[1] = natLong; return v; });
        }
        if (ev != null && isTrackedEvent(baseType)) {
            long capturedBirthId = ((long)(int) ev[5] << 32) | ((int) ev[6] & 0xFFFFFFFFL);
            IdentityMapper.BirthId nat = IdentityMapper.lookupBirthId(naturalValue);
            long natBirthId = nat != null
                    ? ((long) nat.siteId << 32) | (nat.count & 0xFFFFFFFFL)
                    : Long.MIN_VALUE;
            totalValuedEvents.incrementAndGet();
            if (natBirthId == capturedBirthId) naturalAgreements.incrementAndGet();
            else if (baseType != BinarySchema.Event.ATOMIC_RMW && !isArrayValued) {
                naturalDisagreements.incrementAndGet();
            }
        }
    }

    public static void printFidelityReport() {
        // If the run ends with required trace events still pending and no structural
        // mismatch/dead-role failure was observed, classify the run as incomplete.
        if (!hasDiverged) {
            int pendingWithTail = 0;
            int startedWithTail = 0;
            int activeWithTail = 0;
            int deadStartedOrActiveWithTail = 0;
            int otherWithTail = 0;
            StringBuilder rolesWithTail = new StringBuilder();
            for (Map.Entry<Integer, LinkedList<long[]>> entry : roleQueues.entrySet()) {
                int roleId = entry.getKey();
                LinkedList<long[]> queue = entry.getValue();
                if (queue != null && !queue.isEmpty()) {
                    isIncomplete = true;
                    if (rolesWithTail.length() > 0) rolesWithTail.append(',');
                    rolesWithTail.append(roleId).append('(').append(queue.size()).append(')');

                    boolean isPending = pendingRoles.contains(roleId);
                    boolean isStarted = startedRoles.contains(roleId);
                    boolean isActive = activeRoles.contains(roleId);
                    Thread t = roleIdToThread.get(roleId);
                    boolean deadThread = (t != null && !t.isAlive());

                    if (isPending) pendingWithTail++;
                    else if (isStarted) {
                        startedWithTail++;
                        if (deadThread) deadStartedOrActiveWithTail++;
                    } else if (isActive) {
                        activeWithTail++;
                        if (deadThread) deadStartedOrActiveWithTail++;
                    } else otherWithTail++;
                }
            }

            if (isIncomplete) {
                if (pendingWithTail > 0) {
                    incompleteReason = "role_not_started";
                } else if (deadStartedOrActiveWithTail > 0) {
                    incompleteReason = "thread_exited_with_unconsumed_tail";
                } else if (startedWithTail > 0 || activeWithTail > 0) {
                    incompleteReason = "stalled_with_live_roles";
                } else {
                    incompleteReason = "unconsumed_tail_unknown_owner";
                }
                incompleteDetails = "pending=" + pendingWithTail
                        + ",started=" + startedWithTail
                        + ",active=" + activeWithTail
                        + ",dead_started_or_active=" + deadStartedOrActiveWithTail
                        + ",other=" + otherWithTail
                        + ",tail_roles=" + rolesWithTail;
            }
        }

        long total    = totalValuedEvents.get();
        long agreed   = naturalAgreements.get();
        long naturalDisagreed = naturalDisagreements.get();

        int locs = finalStateMap.size(), matched = 0, unseen = 0;
        for (long[] v : finalStateMap.values()) {
            if (v[1] == Long.MIN_VALUE) { unseen++; continue; }
            if (v[1] == v[0]) matched++;
        }
        // A run "matches" if every tracked write location's last natural value equals
        // the captured final value.  Structural divergence is reported separately.
        int seen = locs - unseen;
        boolean match = (seen > 0) && (matched == seen);

        Properties p = new Properties();
        p.setProperty("match",                 String.valueOf(match));
        p.setProperty("structural_divergence", String.valueOf(hasDiverged));
        p.setProperty("incomplete",            String.valueOf(isIncomplete));
        p.setProperty("incomplete_reason",     incompleteReason);
        p.setProperty("incomplete_details",    incompleteDetails);
        p.setProperty("events_matched",        String.valueOf(eventsMatched.get()));
        p.setProperty("events_total",          String.valueOf(totalEvents));
        p.setProperty("valued_events",         String.valueOf(total));
        p.setProperty("natural_agreements",    String.valueOf(agreed));
        p.setProperty("natural_disagreements", String.valueOf(naturalDisagreed));
        p.setProperty("locations_matched",     String.valueOf(matched));
        p.setProperty("locations_total",       String.valueOf(locs));
        p.setProperty("locations_unseen",      String.valueOf(unseen));

        try (Writer w = new FileWriter(fidelityOutputPath)) {
            p.store(w, "Replay Fidelity Result");
        } catch (IOException e) {
            System.err.println("[FIDELITY] Failed to write result file: " + e.getMessage());
        }
    }

    private static boolean isReleaseEvent(int packedType) {
        return TraceSemantics.advancesEpoch(packedType);
    }

    private static boolean isRoleBehind(int roleId, long targetEpoch, boolean shouldHaveLiveThread) {
        LinkedList<long[]> queue = roleQueues.get(roleId);
        if (queue == null || queue.isEmpty()) return false;

        long headEpoch = queue.peek()[0] >>> 32;
        if (headEpoch >= targetEpoch) return false;

        if (shouldHaveLiveThread) {
            Thread t = roleIdToThread.get(roleId);
            if (t != null && !t.isAlive()) {
                reportDivergence(roleId, "thread exited with " + queue.size() + " unconsumed events");
                return false;
            }
        }
        return true;
    }

    private static boolean isAnyRoleBehind(long targetEpoch) {
        for (Integer activeId : activeRoles) {
            if (isRoleBehind(activeId, targetEpoch, true)) return true;
        }

        // Also check roles that have called checkIn() but haven't yet called
        // awaitTurn() (so activateRole() hasn't moved them to activeRoles yet).
        // Without this, a freshly-started thread sitting in startedRoles is invisible
        // to the epoch guard and the epoch can race past its early events.
        for (Integer startedId : startedRoles) {
            if (isRoleBehind(startedId, targetEpoch, true)) return true;
        }

        // Pending roles have not checked in yet, but their queue head still
        // represents required earlier-epoch work. A later epoch must not open
        // until that older epoch is fully drained.
        for (Integer pendingId : pendingRoles) {
            if (isRoleBehind(pendingId, targetEpoch, false)) return true;
        }

        return false;
    }
}
