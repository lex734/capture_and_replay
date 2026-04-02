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

                // --- THE ACTIVE EPOCH GUARD ---
                if (eEpoch > releasedEpoch.get()) {
                    // Check if anyone actually "active" is still in a previous epoch
                    if (!isAnyRoleBehind(eEpoch)) {
                        // We are the leader or the only one left. Advance the timeline.
                        System.out.println("[Replay] Role " + roleId + " advancing Epoch to " + eEpoch + " for type " + (eType & 0xFF));
                        releasedEpoch.set(eEpoch);
                        // Now that releasedEpoch == eEpoch, the loop continues to the match logic
                    } else {
                        // A physical dependency exists. We MUST wait for the laggard.
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

                // --- DIVERGENCE ---
                System.err.println(String.format("[DIVERGENCE] Role %d at Epoch %d. Trace wants Type %d, Code did Type %d",
                                roleId, eEpoch, eType & 0xFF, packedType & 0xFF));
                throw new RuntimeException("Replay Divergence");
            }
        }
    }

    public static int awaitTurnInt(int roleId, int packedType, int objSite, int objCount) {
        activateRole(roleId);
        LinkedList<long[]> myQueue = roleQueues.get(roleId);
        if (myQueue == null) return 0;

        synchronized (controlLock) {
            while (true) {
                long[] expected = myQueue.peek();
                if (expected == null) return 0;

                long eSeq = expected[0];
                long eEpoch = eSeq >>> 32;

                if (eEpoch > releasedEpoch.get()) {
                    try { controlLock.wait(100); continue; } 
                    catch (InterruptedException e) { return 0; }
                }

                if (packedType == (int) expected[2] && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    myQueue.poll();
                    int returnValue = (int) expected[6]; // data2
                    
                    lastMatchedSeq.set(eSeq);
                    if (isReleaseEvent(packedType)) releasedEpoch.set(eEpoch);
                    
                    controlLock.notifyAll();
                    return returnValue;
                }
                throw new RuntimeException("Divergence in awaitTurnInt for Role " + roleId);
            }
        }
    }

    public static long awaitTurnLong(int roleId, int packedType, int objSite, int objCount) {
        activateRole(roleId);
        LinkedList<long[]> myQueue = roleQueues.get(roleId);
        if (myQueue == null) return 0L;

        synchronized (controlLock) {
            while (true) {
                long[] expected = myQueue.peek();
                if (expected == null) return 0L;

                if ((expected[0] >>> 32) > releasedEpoch.get()) {
                    try { controlLock.wait(100); continue; } catch (Exception e) { return 0L; }
                }

                if (packedType == (int) expected[2] && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    myQueue.poll();
                    long high = (long) expected[5] << 32; // data1
                    long low = expected[6] & 0xFFFFFFFFL; // data2
                    long returnValue = high | low;

                    lastMatchedSeq.set(expected[0]);
                    if (isReleaseEvent(packedType)) releasedEpoch.set(expected[0] >>> 32);
                    
                    controlLock.notifyAll();
                    return returnValue;
                }
                throw new RuntimeException("Divergence in awaitTurnLong for Role " + roleId);
            }
        }
    }

    public static Object awaitTurnObj(int roleId, int packedType, int objSite, int objCount) {
        activateRole(roleId);
        LinkedList<long[]> myQueue = roleQueues.get(roleId);
        if (myQueue == null) return null;

        synchronized (controlLock) {
            while (true) {
                long[] expected = myQueue.peek();
                if (expected == null) return null;

                if ((expected[0] >>> 32) > releasedEpoch.get()) {
                    try { controlLock.wait(100); continue; } catch (Exception e) { return null; }
                }

                if (packedType == (int) expected[2] && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    myQueue.poll();
                    int valueSiteId = (int) expected[5];
                    int valueCount = (int) expected[6];
                    Object returnValue = IdentityMapper.resolveByBirthId(valueSiteId, valueCount);

                    lastMatchedSeq.set(expected[0]);
                    if (isReleaseEvent(packedType)) releasedEpoch.set(expected[0] >>> 32);
                    
                    controlLock.notifyAll();
                    return returnValue;
                }
                throw new RuntimeException("Divergence in awaitTurnObj for Role " + roleId);
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

                // --- THE CORRECTED GUARD ---
                if (eEpoch > releasedEpoch.get()) {
                    // If I'm the only one active, or no other active role is still in a lower epoch,
                    // I am allowed to 'Open' this new epoch.
                    if (!isAnyRoleBehind(eEpoch)) {
                        System.out.println("[Replay] Role " + roleId + " advancing global epoch to " + eEpoch);
                        releasedEpoch.set(eEpoch);
                        // No 'continue' needed, just fall through to the match logic
                    } else {
                        // Someone else is still working on Epoch 0. I MUST wait for them.
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

                // DIVERGENCE CHECK...
                throw new RuntimeException("Divergence at Role " + roleId);
            }
        }
    }

    public static int awaitTurnFieldInt(int roleId, int packedType, int objSite, int objCount) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        return (ev == null) ? 0 : (int) ev[6]; // data2
    }

    public static long awaitTurnFieldLong(int roleId, int packedType, int objSite, int objCount) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        if (ev == null) return 0L;
        return ((long) ev[5] << 32) | (ev[6] & 0xFFFFFFFFL); // data1<<32 | data2
    }

    public static Object awaitTurnFieldObj(int roleId, int packedType, int objSite, int objCount) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        if (ev == null) return null;
        return IdentityMapper.resolveByBirthId((int) ev[5], (int) ev[6]); // data1=siteId, data2=count
    }

    // ---- Value-returning array turn methods ----
    // Array records use the IS_ARRAY_VALUED packing:
    //   packedType upper 16 bits = birthId.siteId
    //   objSite = birthId.count,  objCount = index

    public static int awaitTurnArrayInt(int roleId, int packedType, int objSite, int objCount) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        return (ev == null) ? 0 : (int) ev[6];
    }

    public static long awaitTurnArrayLong(int roleId, int packedType, int objSite, int objCount) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        if (ev == null) return 0L;
        return ((long) ev[5] << 32) | (ev[6] & 0xFFFFFFFFL);
    }

    public static Object awaitTurnArrayObj(int roleId, int packedType, int objSite, int objCount) {
        long[] ev = doAwaitTurnValued(roleId, packedType, objSite, objCount);
        if (ev == null) return null;
        return IdentityMapper.resolveByBirthId((int) ev[5], (int) ev[6]);
    }

    private static boolean isReleaseEvent(int packedType) {
        int eventType = packedType & 0xFF;
        return eventType == BinarySchema.Event.MONITOR_EXIT
                || eventType == BinarySchema.Event.THREAD_START
                || eventType == BinarySchema.Event.THREAD_NOTIFY
                || eventType == BinarySchema.Event.THREAD_NOTIFY_ALL
                || eventType == BinarySchema.Event.THREAD_UNPARK
                || eventType == BinarySchema.Event.THREAD_INTERRUPT
                || eventType == BinarySchema.Event.THREAD_WAKEUP
                || eventType == BinarySchema.Event.CLASS_INIT_END
                || eventType == BinarySchema.Event.ATOMIC_WRITE
                || eventType == BinarySchema.Event.ATOMIC_RMW;
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


