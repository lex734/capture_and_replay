package replay;

import common.BinarySchema;
import common.IdentityMapper;
import common.TraceSemantics;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ReplayCoordinator {
    // Pre-sorted boundary event array: each element is [seq, roleId, packedType,
    // objSite, objCount, data1, data2].
    private static long[][] sortedBoundaryEvents;
    private static long totalEvents;
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
    private static final Set<Integer> scheduleRoles = ConcurrentHashMap.newKeySet();

    // Map each role to its own sequence of events
    private static final Map<Integer, LinkedList<long[]>> roleQueues = new ConcurrentHashMap<>();
    private static final AtomicLong eventsMatched = new AtomicLong(0);
    
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
    private static final HashMap<BoundaryKey, Integer> matchedScheduleBoundaryCounts = new HashMap<>();

    private static final class BoundaryKey {
        private final int roleId;
        private final int eventType;
        private final String className;
        private final String methodName;

        private BoundaryKey(int roleId, int eventType, String className, String methodName) {
            this.roleId = roleId;
            this.eventType = eventType;
            this.className = className;
            this.methodName = methodName;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof BoundaryKey)) return false;
            BoundaryKey other = (BoundaryKey) obj;
            return roleId == other.roleId
                    && eventType == other.eventType
                    && Objects.equals(className, other.className)
                    && Objects.equals(methodName, other.methodName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleId, eventType, className, methodName);
        }
    }

    public static boolean hasDiverged() { return hasDiverged; }

    public static void init(MappedByteBuffer traceBuffer, long count) {
        // Clear all state so init() is safe to call more than once (e.g. in tests).
        sortedBoundaryEvents = null;
        totalEvents = 0;
        releasedEpoch.set(0);
        pendingTargetEpoch.set(Long.MAX_VALUE);
        pendingRoles.clear();
        activeRoles.clear();
        startedRoles.clear();
        scheduleRoles.clear();
        roleQueues.clear();
        roleIdToThread.clear();
        hasDiverged = false;
        firstDivergenceInfo = null;
        isIncomplete = false;
        incompleteReason = "";
        incompleteDetails = "";
        currentScheduleIdx.set(0);
        matchedScheduleBoundaryCounts.clear();
        eventsMatched.set(0);
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
        sortedBoundaryEvents = boundaryEvents.toArray(new long[0][]);

        // Distribute replay boundaries into per-role queues. Schedule replay no longer
        // coordinates non-boundary events.
        for (long[] event : boundaryEvents) {
            int rId = (int) event[1];
            roleQueues.computeIfAbsent(rId, k -> new LinkedList<>()).add(event);
            pendingRoles.add(rId); 
        }

        totalEvents = boundaryEvents.size();
        // System.out.println("[Replay] Distributed " + totalEvents + " events into " + roleQueues.keySet().size() + " role queues.");

    }

    public static void loadScheduleArtifact(List<ScheduleDistiller.ScheduleEntry> entries) {
        synchronized (controlLock) {
            scheduleEntries.clear();
            scheduleEntries.addAll(entries);
            scheduleEntries.sort(Comparator.comparingLong(e -> e.seq));
            scheduleRoles.clear();
            for (ScheduleDistiller.ScheduleEntry entry : scheduleEntries) {
                scheduleRoles.add(entry.roleId);
            }
            currentScheduleIdx.set(0);
            matchedScheduleBoundaryCounts.clear();
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

    private static void reportInapplicable(int roleId, String extra) {
        if (hasDiverged) return;
        synchronized (controlLock) {
            if (hasDiverged) return;
            hasDiverged = true;
            firstDivergenceInfo = "role=" + roleId + " | " + extra;
            System.err.println("[INAPPLICABLE] Replay is dynamically inapplicable: " + firstDivergenceInfo);
            System.err.println("[INAPPLICABLE] Synchronization guidance will no longer be applied.");
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
        if (sortedBoundaryEvents == null || sortedBoundaryEvents.length == 0) return;
        int mainRole = (int) sortedBoundaryEvents[0][1];
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
     * Schedule replay primarily coordinates through {@link #awaitScheduleBoundary}.
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
                    eventsMatched.incrementAndGet();
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

    public static void awaitScheduleBoundary(int roleId, int packedType,
            String className, String methodName) {
        if (!scheduleRoles.contains(roleId)) {
            reportInapplicable(roleId,
                    "role is not present in distilled schedule at boundary "
                            + className + "." + methodName);
            return;
        }
        activateRole(roleId);
        int eventType = packedType & 0xFF;
        BoundaryKey actualKey = new BoundaryKey(roleId, eventType, className, methodName);

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

                if (expected.eventType == eventType
                        && expected.className.equals(className)
                        && expected.methodName.equals(methodName)) {
                    currentScheduleIdx.incrementAndGet();
                    lastMatchedSeq.set(expected.seq);
                    consumeMatchedBoundary(roleId, expected.seq, packedType);
                    controlLock.notifyAll();
                    return;
                }

                reportDivergence(roleId, expected.epoch, expected.eventType, eventType,
                        "schedule boundary mismatch: expected="
                                + expected.className + "." + expected.methodName
                                + " actual=" + className + "." + methodName);
                return;
            }
        }
    }

    private static void consumeMatchedBoundary(int roleId, long expectedSeq, int packedType) {
        LinkedList<long[]> myQueue = roleQueues.get(roleId);
        if (myQueue == null) return;
        long[] head = myQueue.peek();
        if (head == null) return;
        if (head[0] != expectedSeq) return;

        myQueue.poll();
        eventsMatched.incrementAndGet();

        int headPackedType = (int) head[2];
        long epoch = expectedSeq >>> 32;
        if (isReleaseEvent(headPackedType)) {
            releasedEpoch.set(epoch);
            pendingTargetEpoch.compareAndSet(epoch, Long.MAX_VALUE);
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
