package replay;

import common.BinarySchema;
import common.IdentityMapper;

import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class ReplayCoordinator {
    // Pre-sorted event array: each element is [seq, roleId, packedType, objSite,
    // objCount, data]
    private static long[][] sortedEvents;
    private static long totalEvents;
    private static final AtomicLong currentIdx = new AtomicLong(0);
    private static final ReentrantLock controlLock = new ReentrantLock();

    // Per-role condition: a thread waiting for its turn parks here.
    // Signalled precisely when currentIdx advances to that role's next event.
    private static final ConcurrentHashMap<Integer, Condition> roleConditions = new ConcurrentHashMap<>();

    // Signalled for system-level state changes: thread check-in, thread death,
    // role activation — used by threads polling the startedRoles transition.
    private static final Condition anyChange = controlLock.newCondition();

    // Stores the seq of the last matched event for the calling thread,
    // so ReplayMonitor can print seq after awaitTurn returns.
    private static final ThreadLocal<Long> lastMatchedSeq = ThreadLocal.withInitial(() -> 0L);

    public static long getLastMatchedSeq() { return lastMatchedSeq.get(); }

    // Roles seen in the trace that haven't checked in yet
    private static final Set<Integer> pendingRoles = ConcurrentHashMap.newKeySet();
    // Roles that have called awaitTurn at least once — they're alive in replay
    private static final Set<Integer> activeRoles = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> startedRoles = ConcurrentHashMap.newKeySet();

    private static Condition conditionFor(int roleId) {
        return roleConditions.computeIfAbsent(roleId, k -> controlLock.newCondition());
    }

    /**
     * Signal the condition for the role expected at the current idx so it can
     * immediately take its turn, and broadcast any system-state change to threads
     * that are polling the startedRoles transition.
     *
     * Must be called while holding controlLock.
     */
    private static void signalNext() {
        long idx = currentIdx.get();
        if (idx < totalEvents) {
            int nextRole = (int) sortedEvents[(int) idx][1];
            Condition c = roleConditions.get(nextRole);
            if (c != null) c.signal();
        }
        anyChange.signalAll();
    }

    public static void init(MappedByteBuffer traceBuffer, long count) {
        // Reset all coordinator state so repeated calls (e.g. same JVM) start clean.
        currentIdx.set(0);
        roleConditions.clear();
        pendingRoles.clear();
        activeRoles.clear();
        startedRoles.clear();

        // Read all non-empty records from the buffer.
        // With batched slot allocation, records may not be contiguous —
        // zero-filled gaps exist where batch tails were unused.
        ArrayList<long[]> events = new ArrayList<>();
        long maxSlots = traceBuffer.capacity() / BinarySchema.RECORD_SIZE;

        for (long i = 0; i < maxSlots; i++) {
            int pos = (int) (i * BinarySchema.RECORD_SIZE);
            long seq = traceBuffer.getLong(pos);
            long roleId = traceBuffer.getLong(pos + 8);
            int packedType = traceBuffer.getInt(pos + 16);
            int objSite = traceBuffer.getInt(pos + 20);
            int objCount = traceBuffer.getInt(pos + 24);
            int data1 = traceBuffer.getInt(pos + 28);
            int data2 = traceBuffer.getInt(pos + 32);
            int data3 = traceBuffer.getInt(pos + 36);
            int data4 = traceBuffer.getInt(pos + 40);

            // Skip zero-filled slots (unused batch tails)
            if (seq == 0 && roleId == 0 && packedType == 0)
                continue;

            events.add(new long[] { seq, roleId, packedType, objSite, objCount, data1, data2, data3, data4, i });
        }

        // Sort by global total-order sequence number.
        //
        // Each event is assigned a globally unique, monotonically increasing seq
        // at capture time, reflecting the actual execution order across all threads.
        // This eliminates the within-epoch ambiguity of the old causal-order scheme
        // and makes intermediate states fully deterministic during replay.
        //
        // Happens-before structure for future validity analysis can be reconstructed
        // from event types (MONITOR_EXIT/ENTER pairs, THREAD_START, etc.) and object
        // identity (objSite/objCount) — no separate epoch storage is needed.
        events.sort((a, b) -> Long.compare(a[0], b[0]));

        sortedEvents = events.toArray(new long[0][]);
        totalEvents = sortedEvents.length;

        System.out.println("[ReplayCoordinator] Loaded and sorted " + totalEvents + " events.");

        // must not advance past their first event until they arrive.
        for (long[] event : sortedEvents) {
            pendingRoles.add((int) event[1]); // event[1] = roleId
        }
        System.out.println("[ReplayCoordinator] Expecting roles: " + pendingRoles);
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

    // Returns the roleId of the earliest pending role that hasn't been claimed yet.
    // Skips roles already in startedRoles to prevent two threads being assigned the same role.
    public static int peekNextPendingRole() {
        for (long[] event : sortedEvents) {
            int roleId = (int) event[1];
            if (pendingRoles.contains(roleId) && !startedRoles.contains(roleId))
                return roleId;
        }
        return -1;
    }

    // In ReplayCoordinator — called from IdentityMapper on first role assignment
    public static void checkIn(int roleId) {
        if (pendingRoles.remove(roleId)) {
            startedRoles.add(roleId);
            controlLock.lock();
            try {
                System.out.println("[Replay] role=" + roleId + " started, waiting to be scheduled.");
                signalNext();
            } finally {
                controlLock.unlock();
            }
        }
    }

    /** Called by the UncaughtExceptionHandler when a replay thread dies early. */
    public static void reportThreadDead(int roleId) {
        activeRoles.remove(roleId);
        startedRoles.remove(roleId);
        pendingRoles.remove(roleId);
        System.err.println("[Replay] role=" + roleId + " died (uncaught exception) — skipping its remaining events.");
        controlLock.lock();
        try {
            signalNext();
        } finally {
            controlLock.unlock();
        }
    }

    private static void activateRole(int roleId) {
        if (startedRoles.remove(roleId)) {
            pendingRoles.remove(roleId);
            activeRoles.add(roleId);
            System.out.println("[Replay] role=" + roleId + " active.");
            // Wake any thread blocked in the startedRoles wait below, and signal
            // the new next-expected role in case it is now this one.
            signalNext();
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
        awaitTurn(roleId, packedType, objSite, objCount, data, null);
    }

    /**
     * Like awaitTurn, but executes {@code onMatch} within the lock before
     * advancing currentIdx. Used for atomic writes so the side-effect (the
     * actual set() on the atomic object) is part of the same ordered step as
     * the trace event — no gap where another thread can slip its write in first.
     */
    public static void awaitTurn(int roleId, int packedType, int objSite, int objCount, int data, Runnable onMatch) {
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            // System.out.println(String.format("[awaitTurn] role=%d type=%d objSite=%d objCount=%d data=%d",
            //         roleId, packedType & 0xFF, objSite, objCount, data));
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents)
                    return;

                long[] expected = sortedEvents[(int) idx];
                int expectedRole = (int) expected[1];
                int expectedType = (int) expected[2];
                // System.out.println(String.format("[check] idx=%-4d expects role=%d type=%d objSite=%d objCount=%d data1=%d data2=%d",
                //     idx, expectedRole, expectedType & 0xFF, expected[3], expected[4], expected[5], expected[6]));
                // Block advancing past an event whose role hasn't checked in yet —
                // that thread exists in the trace but hasn't started in replay yet.
                // Give it time to start rather than deadlocking immediately.
                if (pendingRoles.contains(expectedRole)) {
                    // Truly missing — never started, deadlock
                    System.err.println("[Replay DEADLOCK] Role=" + expectedRole + " never started.");
                    currentIdx.incrementAndGet();
                    signalNext();
                    continue;
                }

                if (startedRoles.contains(expectedRole)) {
                    // Thread exists but hasn't been scheduled yet — wait for it to
                    // call awaitTurn (which triggers activateRole and signals anyChange).
                    // Keep a short timeout as a safety net against lost signals.
                    try { anyChange.await(10, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    continue;
                }

                // Role is not pending, not started, and not active — it died early.
                // Skip its remaining events so other threads can make progress.
                if (!activeRoles.contains(expectedRole)) {
                    System.err.println("[Replay] role=" + expectedRole + " is dead, skipping event at idx=" + idx);
                    currentIdx.incrementAndGet();
                    signalNext();
                    continue;
                }

                // Normal strict total-order match. Also require the event-specific
                // data payload (e.g. array index or sync site id) to match so
                // events on the same object but different indices cannot be
                // matched out-of-order.
                int eventId = expectedType & 0xFF;
                boolean payloadMatches;
                if (eventId == BinarySchema.Event.THREAD_START) {
                    // THREAD_START stores (childRoleId, site) in data1/data2.
                    // Replay calls checkSync with the site as the single "data" arg,
                    // so match against data2 (expected[6]).
                    payloadMatches = ((int) expected[6] == data);
                } else {
                    // For other single-payload events (fields, arrays, class-init,
                    // exceptions) the canonical payload is in data1 (expected[5]).
                    // Accept old traces that put the payload in data2 when data1==0.
                    payloadMatches = (((int) expected[5] == data) || ((int) expected[5] == 0 && (int) expected[6] == data));
                }

                if (roleId == expectedRole && packedType == expectedType
                    && (int) expected[3] == objSite && (int) expected[4] == objCount
                    && payloadMatches) {
                    lastMatchedSeq.set(expected[0]);
                    if (onMatch != null) {
                        try { onMatch.run(); }
                        catch (Exception e) { e.printStackTrace(); }
                    }
                    currentIdx.incrementAndGet();
                    signalNext();
                    return;
                }

                System.err.println(String.format("[WAIT]  idx=%-4d expects role=%d type=%d  got role=%d type=%d",
                        idx, expectedRole, expectedType & 0xFF, roleId, packedType & 0xFF));

                // Park until currentIdx advances to this role's turn.
                try {
                    myTurn.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            controlLock.unlock();
        }
    }

    public static int awaitTurnInt(int roleId, int packedType, int objSite, int objCount) {
        return awaitTurnInt(roleId, packedType, objSite, objCount, null);
    }

    /**
     * Like awaitTurnInt, but executes {@code onMatch} within controlLock before
     * advancing currentIdx.  {@code onMatch} receives data1 (the post-operation
     * value stored during capture) so that RMW replay can write the correct
     * post-op value back to the atomic cell without racing against the next
     * thread's write.  Returns data2 (the captured return value).
     */
    public static int awaitTurnInt(int roleId, int packedType, int objSite, int objCount,
                                   java.util.function.IntConsumer onMatch) {
        controlLock.lock();
        try {
            Condition myTurn = conditionFor(roleId);
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents)
                    return 0;
                long[] expected = sortedEvents[(int) idx];

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && (int) expected[3] == objSite && (int) expected[4] == objCount) {
                    lastMatchedSeq.set(expected[0]);
                    int postOpValue = (int) expected[5]; // data1
                    int returnValue = (int) expected[6]; // data2
                    if (onMatch != null) {
                        try { onMatch.accept(postOpValue); }
                        catch (Exception e) { e.printStackTrace(); }
                    }
                    currentIdx.incrementAndGet();
                    signalNext();
                    return returnValue;
                }
                try {
                    myTurn.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }
        } finally {
            controlLock.unlock();
        }
    }

    public static long awaitTurnLong(int roleId, int packedType, int objSite, int objCount) {
        return awaitTurnLong(roleId, packedType, objSite, objCount, null);
    }

    /**
     * Like awaitTurnLong, but executes {@code onMatch} within controlLock before
     * advancing currentIdx.  {@code onMatch} receives data3/data4 reassembled as
     * the post-operation cell value so that CAS replay can write the correct
     * post-op value back to the atomic cell.  Returns data1/data2 (return value).
     */
    public static long awaitTurnLong(int roleId, int packedType, int objSite, int objCount,
                                     java.util.function.LongConsumer onMatch) {
        controlLock.lock();
        try {
            Condition myTurn = conditionFor(roleId);
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents)
                    return 0L;
                long[] expected = sortedEvents[(int) idx];

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && (int) expected[3] == objSite && (int) expected[4] == objCount) {
                    lastMatchedSeq.set(expected[0]);
                    long returnValue = ((long) expected[5] << 32) | (expected[6] & 0xFFFFFFFFL);
                    if (onMatch != null) {
                        long postOpValue = ((long) expected[7] << 32) | (expected[8] & 0xFFFFFFFFL);
                        try { onMatch.accept(postOpValue); }
                        catch (Exception e) { e.printStackTrace(); }
                    }
                    currentIdx.incrementAndGet();
                    signalNext();
                    return returnValue;
                }
                try {
                    myTurn.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0L;
                }
            }
        } finally {
            controlLock.unlock();
        }
    }

    /**
     * Acquires controlLock and waits for this thread's turn, keeping the lock
     * held so the caller can execute the native atomic call atomically with the
     * ordering step.  Must be paired with a call to {@link #endAtomicReplay()}.
     * <p>
     * Mirrors the capture-side beginAtomicCapture/endAtomicCapture* pattern.
     * The lock is intentionally NOT released on normal return; it is released
     * inside endAtomicReplay().  On an unexpected exception the lock is released
     * before propagating so the coordinator does not deadlock.
     */
    public static void beginAtomicReplay(int roleId, int packedType, int objSite, int objCount) {
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents)
                    return; // trace exhausted — proceed without ordering
                long[] expected = sortedEvents[(int) idx];
                int expectedRole = (int) expected[1];
                int expectedType = (int) expected[2];

                if (pendingRoles.contains(expectedRole)) {
                    System.err.println("[Replay DEADLOCK] Role=" + expectedRole + " never started.");
                    currentIdx.incrementAndGet();
                    signalNext();
                    continue;
                }
                if (startedRoles.contains(expectedRole)) {
                    try { anyChange.await(10, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    continue;
                }
                if (!activeRoles.contains(expectedRole)) {
                    System.err.println("[Replay] role=" + expectedRole + " is dead, skipping event at idx=" + idx);
                    currentIdx.incrementAndGet();
                    signalNext();
                    continue;
                }
                if (roleId == expectedRole && packedType == expectedType
                        && (int) expected[3] == objSite && (int) expected[4] == objCount) {
                    lastMatchedSeq.set(expected[0]);
                    return; // matched — lock still held, endAtomicReplay() will advance + release
                }
                System.err.println(String.format("[WAIT]  idx=%-4d expects role=%d type=%d  got role=%d type=%d",
                        idx, expectedRole, expectedType & 0xFF, roleId, packedType & 0xFF));
                try { myTurn.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        } catch (RuntimeException | Error e) {
            controlLock.unlock(); // release on unexpected exception to avoid deadlock
            throw e;
        }
        // Lock is intentionally still held on normal return
    }

    /**
     * Advances the total-order index and releases the controlLock acquired by
     * {@link #beginAtomicReplay}.  Must be called exactly once per
     * beginAtomicReplay() call.
     */
    public static void endAtomicReplay() {
        try {
            currentIdx.incrementAndGet();
            signalNext();
        } finally {
            controlLock.unlock();
        }
    }

    public static Object awaitTurnObj(int roleId, int packedType, int objSite, int objCount) {
        return awaitTurnObj(roleId, packedType, objSite, objCount, null);
    }

    /**
     * Like awaitTurnObj, but executes {@code onMatch} within controlLock before
     * advancing currentIdx. {@code onMatch} receives the post-op object resolved
     * from data3/data4 so that RMW replay can write the correct post-op value back
     * to the atomic cell without racing against the next thread's write.
     * Returns the captured return value resolved from data1/data2.
     */
    public static Object awaitTurnObj(int roleId, int packedType, int objSite, int objCount,
                                      java.util.function.Consumer<Object> onMatch) {
        controlLock.lock();
        try {
            Condition myTurn = conditionFor(roleId);
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents)
                    return null;
                long[] expected = sortedEvents[(int) idx];

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && (int) expected[3] == objSite && (int) expected[4] == objCount) {
                    lastMatchedSeq.set(expected[0]);
                    int valueSiteId = (int) expected[5]; // data1 = return value siteId
                    int valueCount  = (int) expected[6]; // data2 = return value count
                    Object returnValue = IdentityMapper.resolveByBirthId(valueSiteId, valueCount);
                    if (onMatch != null) {
                        int postOpSiteId = (int) expected[7]; // data3
                        int postOpCount  = (int) expected[8]; // data4
                        Object postOpValue = IdentityMapper.resolveByBirthId(postOpSiteId, postOpCount);
                        try { onMatch.accept(postOpValue); }
                        catch (Exception e) { e.printStackTrace(); }
                    }
                    currentIdx.incrementAndGet();
                    signalNext();
                    return returnValue;
                }
                try {
                    myTurn.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        } finally {
            controlLock.unlock();
        }
    }

}
