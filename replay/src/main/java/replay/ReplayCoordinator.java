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
    // Pre-sorted event array: each element is [seq, roleId, packedType, objSite, objCount, data1..data4, slotIdx, creatorRoles]
    private static long[][] sortedEvents;
    private static long totalEvents;
    private static final AtomicLong currentIdx = new AtomicLong(0);
    private static final ReentrantLock controlLock = new ReentrantLock();

    // Order-correctness counters.
    private static final AtomicLong matchedCount = new AtomicLong(0);
    private static final AtomicLong skippedCount = new AtomicLong(0);
    private static long sequenceHash = 17L;

    // Per-role condition: a thread waiting for its turn parks here.
    private static final ConcurrentHashMap<Integer, Condition> roleConditions = new ConcurrentHashMap<>();

    // Signalled for system-level state changes: thread check-in, thread death, role activation.
    private static final Condition anyChange = controlLock.newCondition();

    // Stores the seq of the last matched event for the calling thread.
    private static final ThreadLocal<Long> lastMatchedSeq = ThreadLocal.withInitial(() -> 0L);

    public static long getLastMatchedSeq() { return lastMatchedSeq.get(); }

    // Role lifecycle sets:
    //   pendingRoles  – seen in trace, not yet started in replay
    //   startedRoles  – thread has been spawned but hasn't called awaitTurn yet
    //   activeRoles   – has called awaitTurn at least once; considered alive
    private static final Set<Integer> pendingRoles  = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> activeRoles   = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> startedRoles  = ConcurrentHashMap.newKeySet();

    private static Condition conditionFor(int roleId) {
        return roleConditions.computeIfAbsent(roleId, k -> controlLock.newCondition());
    }

    /**
     * Signal the role expected at the current idx so it can take its turn,
     * and broadcast any system-state change to threads polling startedRoles.
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
        currentIdx.set(0);
        matchedCount.set(0);
        skippedCount.set(0);
        sequenceHash = 17L;
        roleConditions.clear();
        pendingRoles.clear();
        activeRoles.clear();
        startedRoles.clear();

        ArrayList<long[]> events = new ArrayList<>();
        long maxSlots = traceBuffer.capacity() / BinarySchema.RECORD_SIZE;

        for (long i = 0; i < maxSlots; i++) {
            int pos = (int) (i * BinarySchema.RECORD_SIZE);
            long seq      = traceBuffer.getLong(pos);
            long roleId   = traceBuffer.getLong(pos + 8);
            int packedType = traceBuffer.getInt(pos + 16);
            int objSite   = traceBuffer.getInt(pos + 20);
            int objCount  = traceBuffer.getInt(pos + 24);
            int data1     = traceBuffer.getInt(pos + 28);
            int data2     = traceBuffer.getInt(pos + 32);
            int data3     = traceBuffer.getInt(pos + 36);
            int data4     = traceBuffer.getInt(pos + 40);

            if (seq == 0 && roleId == 0 && packedType == 0) continue;

            int creatorRoles = traceBuffer.getInt(pos + 44);
            events.add(new long[] { seq, roleId, packedType, objSite, objCount, data1, data2, data3, data4, i, creatorRoles });
        }

        events.sort((a, b) -> Long.compare(a[0], b[0]));
        sortedEvents = events.toArray(new long[0][]);
        totalEvents  = sortedEvents.length;

        for (long[] event : sortedEvents) {
            pendingRoles.add((int) event[1]);
        }
    }

    /**
     * Registers the main thread so it is immediately active (no checkIn needed).
     */
    public static void registerMainThread(long mainTid) {
        if (sortedEvents == null || sortedEvents.length == 0) return;
        int mainRole = (int) sortedEvents[0][1];
        pendingRoles.remove(mainRole);
        activeRoles.add(mainRole);
        IdentityMapper.preAssignRole(mainTid, mainRole);
    }

    /** Returns the roleId of the earliest pending role that hasn't been claimed yet. */
    public static int peekNextPendingRole() {
        for (long[] event : sortedEvents) {
            int roleId = (int) event[1];
            if (pendingRoles.contains(roleId) && !startedRoles.contains(roleId))
                return roleId;
        }
        return -1;
    }

    /** Called from IdentityMapper on first role assignment for a newly spawned thread. */
    public static void checkIn(int roleId) {
        if (pendingRoles.remove(roleId)) {
            startedRoles.add(roleId);
            controlLock.lock();
            try {
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
        System.err.println("[Replay] role=" + roleId + " died — skipping its remaining events.");
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
            signalNext();
        }
    }

    // ---- Payload matching helpers ----

    private static boolean payloadMatches(long[] expected, int data) {
        int eventId = (int) expected[2] & 0xFF;
        if (eventId == BinarySchema.Event.THREAD_START) {
            // data2 stores the call-site; match against it
            return ((int) expected[6] == data);
        }
        if (eventId == BinarySchema.Event.MONITOR_EXIT
                || eventId == BinarySchema.Event.THREAD_WAIT
                || eventId == BinarySchema.Event.THREAD_NOTIFY
                || eventId == BinarySchema.Event.THREAD_NOTIFY_ALL) {
            return true;
        }
        // General case: data1 is canonical; fall back to data2 for old traces where data1==0.
        return ((int) expected[5] == data) || ((int) expected[5] == 0 && (int) expected[6] == data);
    }

    private static boolean matchesEvent(long[] expected, int roleId, int packedType,
                                        int objSite, int objCount, int data) {
        return roleId      == (int) expected[1]
            && packedType  == (int) expected[2]
            && objSite     == (int) expected[3]
            && objCount    == (int) expected[4]
            && payloadMatches(expected, data);
    }

    private static void recordMatch(long[] expected) {
        lastMatchedSeq.set(expected[0]);
        sequenceHash = sequenceHash * 31L + (expected[1] & 0xFFFFL);
        sequenceHash = sequenceHash * 31L + (expected[2] & 0xFFL);
        matchedCount.incrementAndGet();
    }

    // ---- Core scheduling: advance past roles that cannot run ----

    /**
     * Returns true if the event at currentIdx was consumed (skipped or matched).
     * Skips events whose role is pending (never started) or dead.
     * Waits briefly for roles that have started but haven't activated yet.
     * Returns false when the expected role is active — caller must decide whether to match or park.
     *
     * Must be called with controlLock held.
     */
    private static boolean advancePastBlockedRoles() throws InterruptedException {
        long idx = currentIdx.get();
        if (idx >= totalEvents) return false;
        long[] expected = sortedEvents[(int) idx];
        int expectedRole = (int) expected[1];

        if (pendingRoles.contains(expectedRole)) {
            System.err.println("[Replay DEADLOCK] Role=" + expectedRole + " never started.");
            skippedCount.incrementAndGet();
            currentIdx.incrementAndGet();
            signalNext();
            return true;
        }
        if (startedRoles.contains(expectedRole)) {
            anyChange.await(10, TimeUnit.MILLISECONDS);
            return true; // re-check
        }
        if (!activeRoles.contains(expectedRole)) {
            System.err.println("[Replay] role=" + expectedRole + " is dead, skipping idx=" + idx);
            skippedCount.incrementAndGet();
            currentIdx.incrementAndGet();
            signalNext();
            return true;
        }
        return false; // role is active — caller decides
    }

    // ---- Public await API ----

    public static void awaitTurn(int roleId, int packedType, int objSite, int objCount, int data) {
        awaitTurn(roleId, packedType, objSite, objCount, data, null);
    }

    /**
     * Blocks the calling thread until its event is next in the captured total order,
     * then runs {@code onMatch} (if any) atomically before advancing the index.
     */
    public static void awaitTurn(int roleId, int packedType, int objSite, int objCount, int data, Runnable onMatch) {
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            System.out.println(String.format("[awaitTurn] role=%d type=%d objSite=%d objCount=%d data=%d",
                    roleId, packedType & 0xFF, objSite, objCount, data));
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents) return;

                long[] expected = sortedEvents[(int) idx];
                System.out.println(String.format("[check] idx=%-4d expects role=%d type=%d objSite=%d objCount=%d data1=%d data2=%d",
                    idx, (int) expected[1], (int) expected[2] & 0xFF, expected[3], expected[4], expected[5], expected[6]));

                if (advancePastBlockedRoles()) continue;

                if (matchesEvent(expected, roleId, packedType, objSite, objCount, data)) {
                    recordMatch(expected);
                    if (onMatch != null) {
                        try { onMatch.run(); } catch (Exception e) { e.printStackTrace(); }
                    }
                    currentIdx.incrementAndGet();
                    signalNext();
                    return;
                }

                System.err.println(String.format("[WAIT]  idx=%-4d expects role=%d type=%d  got role=%d type=%d",
                        idx, (int) expected[1], (int) expected[2] & 0xFF, roleId, packedType & 0xFF));
                myTurn.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            controlLock.unlock();
        }
    }

    public static int awaitTurnInt(int roleId, int packedType, int objSite, int objCount) {
        return awaitTurnInt(roleId, packedType, objSite, objCount, null);
    }

    /**
     * Like awaitTurn, but returns data2 (the captured return value).
     * {@code onMatch} receives data1 (post-op value) for RMW write-back.
     */
    public static int awaitTurnInt(int roleId, int packedType, int objSite, int objCount,
                                   java.util.function.IntConsumer onMatch) {
        controlLock.lock();
        try {
            Condition myTurn = conditionFor(roleId);
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents) return 0;
                long[] expected = sortedEvents[(int) idx];

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    recordMatch(expected);
                    int postOpValue  = (int) expected[5];
                    int returnValue  = (int) expected[6];
                    if (onMatch != null) {
                        try { onMatch.accept(postOpValue); } catch (Exception e) { e.printStackTrace(); }
                    }
                    currentIdx.incrementAndGet();
                    signalNext();
                    return returnValue;
                }
                myTurn.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            controlLock.unlock();
        }
    }

    public static long awaitTurnLong(int roleId, int packedType, int objSite, int objCount) {
        return awaitTurnLong(roleId, packedType, objSite, objCount, null);
    }

    /**
     * Like awaitTurnLong, but {@code onMatch} receives data3/data4 as the post-op cell value.
     * Returns data1/data2 (return value).
     */
    public static long awaitTurnLong(int roleId, int packedType, int objSite, int objCount,
                                     java.util.function.LongConsumer onMatch) {
        controlLock.lock();
        try {
            Condition myTurn = conditionFor(roleId);
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents) return 0L;
                long[] expected = sortedEvents[(int) idx];

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    recordMatch(expected);
                    long returnValue = ((long) expected[5] << 32) | (expected[6] & 0xFFFFFFFFL);
                    if (onMatch != null) {
                        long postOpValue = ((long) expected[7] << 32) | (expected[8] & 0xFFFFFFFFL);
                        try { onMatch.accept(postOpValue); } catch (Exception e) { e.printStackTrace(); }
                    }
                    currentIdx.incrementAndGet();
                    signalNext();
                    return returnValue;
                }
                myTurn.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0L;
        } finally {
            controlLock.unlock();
        }
    }

    /**
     * Acquires controlLock and waits for this thread's turn, keeping the lock held
     * so the caller can execute the native atomic call atomically with the ordering step.
     * Must be paired with {@link #endAtomicReplay()}.
     */
    public static void beginAtomicReplay(int roleId, int packedType, int objSite, int objCount) {
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents) return;
                long[] expected = sortedEvents[(int) idx];

                if (advancePastBlockedRoles()) continue;

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    recordMatch(expected);
                    return; // lock still held; endAtomicReplay() will advance + release
                }
                System.err.println(String.format("[WAIT]  idx=%-4d expects role=%d type=%d  got role=%d type=%d",
                        idx, (int) expected[1], (int) expected[2] & 0xFF, roleId, packedType & 0xFF));
                myTurn.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            controlLock.unlock(); // release so coordinator doesn't deadlock
        } catch (RuntimeException | Error e) {
            controlLock.unlock();
            throw e;
        }
        // Lock intentionally still held on normal return
    }

    /** Advances the total-order index and releases the controlLock held by {@link #beginAtomicReplay}. */
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
     * Like awaitTurnObj, but {@code onMatch} receives the post-op object for RMW write-back.
     * Returns the captured return value resolved from data1/data2.
     */
    public static Object awaitTurnObj(int roleId, int packedType, int objSite, int objCount,
                                      java.util.function.Consumer<Object> onMatch) {
        controlLock.lock();
        try {
            Condition myTurn = conditionFor(roleId);
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents) return null;
                long[] expected = sortedEvents[(int) idx];

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && objSite == (int) expected[3] && objCount == (int) expected[4]) {
                    recordMatch(expected);
                    int valueSiteId    = (int) expected[5];
                    int valueCount     = (int) expected[6];
                    int creatorRoles   = (int) expected[10];
                    int valueCreator   = (creatorRoles >> 16) & 0xFFFF;
                    int postOpCreator  = creatorRoles & 0xFFFF;
                    Object returnValue = IdentityMapper.resolveByBirthId(valueCreator, valueSiteId, valueCount);
                    if (onMatch != null) {
                        int postOpSiteId = (int) expected[7];
                        int postOpCount  = (int) expected[8];
                        Object postOpValue = IdentityMapper.resolveByBirthId(postOpCreator, postOpSiteId, postOpCount);
                        try { onMatch.accept(postOpValue); } catch (Exception e) { e.printStackTrace(); }
                    }
                    currentIdx.incrementAndGet();
                    signalNext();
                    return returnValue;
                }
                myTurn.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            controlLock.unlock();
        }
    }

    /**
     * Prints a one-line order-correctness summary to stderr.
     * Format: [ReplayStats] matched=X skipped=Y total=Z seqHash=H
     */
    public static void printStats() {
        System.err.println(String.format(
            "[ReplayStats] matched=%d skipped=%d total=%d seqHash=%d",
            matchedCount.get(), skippedCount.get(), totalEvents, sequenceHash));
    }
}
