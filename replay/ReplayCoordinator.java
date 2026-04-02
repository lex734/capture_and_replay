package replay;

import common.BinarySchema;
import common.IdentityMapper;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ReplayCoordinator {
    // Pre-sorted event array: each element is [seq, roleId, packedType, objSite,
    // objCount, data]
    private static long[][] sortedEvents;
    private static long totalEvents;
    private static final AtomicLong currentIdx = new AtomicLong(0);
    private static final Object controlLock = new Object();
    private static final AtomicLong releasedEpoch = new AtomicLong(0);

    // Stores the seq of the last matched event for the calling thread,
    // so ReplayMonitor can print epoch/seq after awaitTurn returns.
    private static final ThreadLocal<Long> lastMatchedSeq = ThreadLocal.withInitial(() -> 0L);

    public static long getLastMatchedSeq() { return lastMatchedSeq.get(); }

    // Roles seen in the trace that haven't checked in yet
    private static final Set<Integer> pendingRoles = ConcurrentHashMap.newKeySet();
    // Roles that have called awaitTurn at least once — they're alive in replay
    private static final Set<Integer> activeRoles = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> startedRoles = ConcurrentHashMap.newKeySet();

    // Map each role to its own sequence of events
    private static final Map<Integer, LinkedList<long[]>> roleQueues = new ConcurrentHashMap<>();
    private static final AtomicLong globalMatchCount = new AtomicLong(0);

    public static void init(MappedByteBuffer traceBuffer, long count) {
        ArrayList<long[]> allEvents = new ArrayList<>();
        long maxSlots = traceBuffer.capacity() / BinarySchema.RECORD_SIZE;

        for (long i = 0; i < maxSlots; i++) {
            int pos = (int) (i * BinarySchema.RECORD_SIZE);
            long seq = traceBuffer.getLong(pos);
            if (seq == 0) continue; // Quick skip

            allEvents.add(new long[] {
                seq,                               // [0] Full Seq (Epoch | Local)
                traceBuffer.getLong(pos + 8),      // [1] roleId
                traceBuffer.getInt(pos + 16),      // [2] packedType
                traceBuffer.getInt(pos + 20),      // [3] objSite
                traceBuffer.getInt(pos + 24),      // [4] objCount
                traceBuffer.getInt(pos + 28),      // [5] data1
                traceBuffer.getInt(pos + 32)       // [6] data2
            });
        }

        // Sort globally once to maintain the 'ideal' trace order
        allEvents.sort(Comparator.comparingLong(a -> a[0]));

        // Distribute into Per-Role Queues
        for (long[] event : allEvents) {
            int rId = (int) event[1];
            roleQueues.computeIfAbsent(rId, k -> new LinkedList<>()).add(event);
            pendingRoles.add(rId); 
        }

        totalEvents = allEvents.size();
        System.out.println("[Replay] Distributed " + totalEvents + " events into " + roleQueues.keySet().size() + " role queues.");
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
        IdentityMapper.preAssignRole(mainTid, mainRole);
        System.out.println("[Replay] main thread registered as role=" + mainRole);
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

    // In ReplayCoordinator — called from IdentityMapper on first role assignment
    public static void checkIn(int roleId) {
        if (pendingRoles.remove(roleId)) {
            startedRoles.add(roleId);
            synchronized (controlLock) {
                System.out.println("[Replay] role=" + roleId + " started, waiting to be scheduled.");
                controlLock.notifyAll();
            }
        }
    }

    /** Called by the UncaughtExceptionHandler when a replay thread dies early. */
    public static void reportThreadDead(int roleId) {
        activeRoles.remove(roleId);
        startedRoles.remove(roleId);
        pendingRoles.remove(roleId);
        System.err.println("[Replay] role=" + roleId + " died (uncaught exception) — skipping its remaining events.");
        synchronized (controlLock) {
            controlLock.notifyAll();
        }
    }

    private static void activateRole(int roleId) {
        if (activeRoles.contains(roleId))
            return;
        System.out.println("[Replay] Activating role=" + roleId);
        synchronized (controlLock) {
            // Re-check after acquiring lock to prevent double-activation
            if (activeRoles.contains(roleId)) return;
            // Move the role to active regardless of its current state (pending or started)
            boolean removed = pendingRoles.remove(roleId) || startedRoles.remove(roleId);
            activeRoles.add(roleId);
            System.out.println("[Replay] role=" + roleId + " is now active.");
            controlLock.notifyAll();
        }
    }

    /**
     * Blocks the calling thread until its event matches the next expected event
     * in the captured total order.
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
                    if (isReleaseEvent(eType) && !isAnyRoleBehind(eEpoch)) {
                        // This release event is the epoch leader — open the new epoch.
                        System.out.println("[Replay] Role " + roleId + " advancing Epoch to " + eEpoch + " for type " + (eType & 0xFF));
                        releasedEpoch.set(eEpoch);
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
                    myQueue.poll(); // Consume the CLASS_INIT_END
                    
                    lastMatchedSeq.set(eSeq);
                    // Standard JMM release check (redundant now, but good for safety)
                    if (isReleaseEvent(eType)) {
                        releasedEpoch.set(eEpoch);
                    }
                    
                    controlLock.notifyAll(); // Wake up anyone waiting for this epoch/seq
                    return;
                }

                // --- DIVERGENCE: consume, log, inject trace value, and continue ---
                myQueue.poll();
                System.err.println(String.format("[DIVERGENCE] Role %d at Epoch %d. Trace wants Type %d, Code did Type %d",
                                roleId, eEpoch, eType & 0xFF, packedType & 0xFF));
                new RuntimeException("[DIVERGENCE] stack trace").printStackTrace(System.err);
                lastMatchedSeq.set(eSeq);
                if (isReleaseEvent(eType)) releasedEpoch.set(eEpoch);
                controlLock.notifyAll();
                return;
            }
        }
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
                    int traceValue = (int) expected[6];
                    lastMatchedSeq.set(eSeq);
                    if (isReleaseEvent(packedType)) releasedEpoch.set(eEpoch);
                    controlLock.notifyAll();
                    if (naturalValue != traceValue) {
                        System.err.println(String.format("[VALUE-DIVERGENCE] awaitTurnInt role=%d: natural=%d trace=%d",
                                roleId, naturalValue, traceValue));
                        return traceValue;
                    }
                    return naturalValue;
                }
                // Type-level divergence: consume, log, inject trace value.
                myQueue.poll();
                System.err.println(String.format("[DIVERGENCE] awaitTurnInt Role %d at Epoch %d. Trace wants Type %d, Code did Type %d",
                                roleId, eEpoch, eType & 0xFF, packedType & 0xFF));
                new RuntimeException("[DIVERGENCE] stack trace").printStackTrace(System.err);
                int divergedValue = (int) expected[6];
                lastMatchedSeq.set(eSeq);
                if (isReleaseEvent(eType)) releasedEpoch.set(eEpoch);
                controlLock.notifyAll();
                return divergedValue;
            }
        }
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
                    long traceValue = ((long) expected[5] << 32) | (expected[6] & 0xFFFFFFFFL);
                    lastMatchedSeq.set(eSeq);
                    if (isReleaseEvent(packedType)) releasedEpoch.set(eEpoch);
                    controlLock.notifyAll();
                    if (naturalValue != traceValue) {
                        System.err.println(String.format("[VALUE-DIVERGENCE] awaitTurnLong role=%d: natural=%d trace=%d",
                                roleId, naturalValue, traceValue));
                        return traceValue;
                    }
                    return naturalValue;
                }
                myQueue.poll();
                System.err.println(String.format("[DIVERGENCE] awaitTurnLong Role %d at Epoch %d. Trace wants Type %d, Code did Type %d",
                                roleId, eEpoch, eType & 0xFF, packedType & 0xFF));
                new RuntimeException("[DIVERGENCE] stack trace").printStackTrace(System.err);
                long divergedValue = ((long) expected[5] << 32) | (expected[6] & 0xFFFFFFFFL);
                lastMatchedSeq.set(eSeq);
                if (isReleaseEvent(eType)) releasedEpoch.set(eEpoch);
                controlLock.notifyAll();
                return divergedValue;
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
                    Object traceValue = IdentityMapper.resolveByBirthId((int) expected[5], (int) expected[6]);
                    lastMatchedSeq.set(eSeq);
                    if (isReleaseEvent(packedType)) releasedEpoch.set(eEpoch);
                    controlLock.notifyAll();
                    if (traceValue == null && naturalValue != null) {
                        IdentityMapper.registerByBirthId((int) expected[5], (int) expected[6], naturalValue);
                        return naturalValue;
                    }
                    if (naturalValue != traceValue) {
                        System.err.println(String.format("[VALUE-DIVERGENCE] awaitTurnObj role=%d: natural=%s trace=%s",
                                roleId, naturalValue, traceValue));
                        return traceValue;
                    }
                    return naturalValue;
                }
                myQueue.poll();
                System.err.println(String.format("[DIVERGENCE] awaitTurnObj Role %d at Epoch %d. Trace wants Type %d, Code did Type %d",
                                roleId, eEpoch, eType & 0xFF, packedType & 0xFF));
                new RuntimeException("[DIVERGENCE] stack trace").printStackTrace(System.err);
                Object divergedValue = IdentityMapper.resolveByBirthId((int) expected[5], (int) expected[6]);
                lastMatchedSeq.set(eSeq);
                if (isReleaseEvent(eType)) releasedEpoch.set(eEpoch);
                controlLock.notifyAll();
                return divergedValue;
            }
        }
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
                long[] expected = myQueue.peek();
                long eSeq = expected[0];
                long eEpoch = eSeq >>> 32;
                int eType = (int) expected[2];

                // --- EPOCH GUARD ---
                // Only release events may advance releasedEpoch. Field/array reads and
                // writes follow thread-local sequence without cross-thread epoch blocking;
                // they wait here only until the release event that opened this epoch fires.
                if (eEpoch > releasedEpoch.get()) {
                    if (isReleaseEvent(eType) && !isAnyRoleBehind(eEpoch)) {
                        System.out.println("[Replay] Role " + roleId + " advancing global epoch to " + eEpoch);
                        releasedEpoch.set(eEpoch);
                    } else {
                        try {
                            controlLock.wait(100);
                            continue;
                        } catch (InterruptedException e) { return null; }
                    }
                }

                // --- THE MATCH ---
                if (packedType == (int) expected[2] && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    myQueue.poll();
                    lastMatchedSeq.set(eSeq);
                    
                    // If this specific event was a release, make sure we update (redundancy)
                    if (isReleaseEvent((int)expected[2])) {
                        releasedEpoch.set(eEpoch);
                    }
                    
                    controlLock.notifyAll();
                    return expected;
                }

                // --- DIVERGENCE: consume, log, inject trace value, and continue ---
                myQueue.poll();
                System.err.println(String.format("[DIVERGENCE] doAwaitTurnValued Role %d at Epoch %d. Trace wants Type %d, Code did Type %d",
                                roleId, eEpoch, (int) expected[2] & 0xFF, packedType & 0xFF));
                new RuntimeException("[DIVERGENCE] stack trace").printStackTrace(System.err);
                lastMatchedSeq.set(eSeq);
                if (isReleaseEvent((int) expected[2])) releasedEpoch.set(eEpoch);
                controlLock.notifyAll();
                return expected;
            }
        }
    }

    public static int awaitTurnFieldInt(int roleId, int packedType, int objSite, int objCount, int naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        if (ev == null) return naturalValue;
        int traceValue = (int) ev[6];
        if (naturalValue != traceValue) {
            System.err.println(String.format("[VALUE-DIVERGENCE] awaitTurnFieldInt role=%d: natural=%d trace=%d",
                    roleId, naturalValue, traceValue));
            return traceValue;
        }
        return naturalValue;
    }

    public static long awaitTurnFieldLong(int roleId, int packedType, int objSite, int objCount, long naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        if (ev == null) return naturalValue;
        long traceValue = ((long) ev[5] << 32) | (ev[6] & 0xFFFFFFFFL);
        if (naturalValue != traceValue) {
            System.err.println(String.format("[VALUE-DIVERGENCE] awaitTurnFieldLong role=%d: natural=%d trace=%d",
                    roleId, naturalValue, traceValue));
            return traceValue;
        }
        return naturalValue;
    }

    public static Object awaitTurnFieldObj(int roleId, int packedType, int objSite, int objCount, Object naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        if (ev == null) return naturalValue;
        Object traceValue = IdentityMapper.resolveByBirthId((int) ev[5], (int) ev[6]);
        if (traceValue == null && naturalValue != null) {
            IdentityMapper.registerByBirthId((int) ev[5], (int) ev[6], naturalValue);
            return naturalValue;
        }
        if (naturalValue != traceValue) {
            System.err.println(String.format("[VALUE-DIVERGENCE] awaitTurnFieldObj role=%d: natural=%s trace=%s",
                    roleId, naturalValue, traceValue));
            return traceValue;
        }
        return naturalValue;
    }

    // ---- Value-returning array turn methods ----
    // Array records use the IS_ARRAY_VALUED packing:
    //   packedType upper 16 bits = birthId.siteId
    //   objSite = birthId.count,  objCount = index

    public static int awaitTurnArrayInt(int roleId, int packedType, int objSite, int objCount, int naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        if (ev == null) return naturalValue;
        int traceValue = (int) ev[6];
        if (naturalValue != traceValue) {
            System.err.println(String.format("[VALUE-DIVERGENCE] awaitTurnArrayInt role=%d: natural=%d trace=%d",
                    roleId, naturalValue, traceValue));
            return traceValue;
        }
        return naturalValue;
    }

    public static long awaitTurnArrayLong(int roleId, int packedType, int objSite, int objCount, long naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        if (ev == null) return naturalValue;
        long traceValue = ((long) ev[5] << 32) | (ev[6] & 0xFFFFFFFFL);
        if (naturalValue != traceValue) {
            System.err.println(String.format("[VALUE-DIVERGENCE] awaitTurnArrayLong role=%d: natural=%d trace=%d",
                    roleId, naturalValue, traceValue));
            return traceValue;
        }
        return naturalValue;
    }

    public static Object awaitTurnArrayObj(int roleId, int packedType, int objSite, int objCount, Object naturalValue) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        if (ev == null) return naturalValue;
        Object traceValue = IdentityMapper.resolveByBirthId((int) ev[5], (int) ev[6]);
        if (traceValue == null && naturalValue != null) {
            IdentityMapper.registerByBirthId((int) ev[5], (int) ev[6], naturalValue);
            return naturalValue;
        }
        if (naturalValue != traceValue) {
            System.err.println(String.format("[VALUE-DIVERGENCE] awaitTurnArrayObj role=%d: natural=%s trace=%s",
                    roleId, naturalValue, traceValue));
            return traceValue;
        }
        return naturalValue;
    }

    private static boolean isReleaseEvent(int packedType) {
        int eventType = packedType & 0xFF;
        int flags = (packedType >> 8) & 0xFF;
        boolean isVolatile = (flags & common.BinarySchema.Flags.IS_VOLATILE) != 0;
        return eventType == BinarySchema.Event.MONITOR_EXIT
                || eventType == BinarySchema.Event.THREAD_START
                || eventType == BinarySchema.Event.THREAD_NOTIFY
                || eventType == BinarySchema.Event.THREAD_NOTIFY_ALL
                || eventType == BinarySchema.Event.THREAD_UNPARK
                || eventType == BinarySchema.Event.THREAD_INTERRUPT
                || eventType == BinarySchema.Event.THREAD_WAKEUP
                || eventType == BinarySchema.Event.CLASS_INIT_END
                || eventType == BinarySchema.Event.ATOMIC_WRITE
                || eventType == BinarySchema.Event.ATOMIC_RMW
                || (eventType == BinarySchema.Event.FIELD_WRITE && isVolatile);
    }

    private static boolean isAnyRoleBehind(long targetEpoch) {
        for (Integer activeId : activeRoles) {
            LinkedList<long[]> queue = roleQueues.get(activeId);
            if (queue != null && !queue.isEmpty()) {
                long headEpoch = queue.peek()[0] >>> 32;
                if (headEpoch < targetEpoch) {
                    // An active thread still has events to process in an older epoch.
                    return true; 
                }
            }
        }
        return false;
    }
}


