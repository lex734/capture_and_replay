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
    private static final int IGNORE_CREATOR_ROLE = Integer.MIN_VALUE;
    private static final long IGNORE_AUX = Long.MIN_VALUE;

    public static class ReplayDivergedException extends RuntimeException {
        ReplayDivergedException(String message) {
            super(message);
        }
    }

    // Pre-sorted event array: [seq, roleId, packedType, objSite, objCount,
    // data1..data4, slotIdx, objCreatorRole, creatorRoles, data5, data6]
    private static long[][] sortedEvents;
    private static long totalEvents;
    private static final AtomicLong currentIdx = new AtomicLong(0);
    private static final ReentrantLock controlLock = new ReentrantLock();
    private static final long STALL_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(
            Long.getLong("replay.stall.timeout.ms", 30000L));

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

    private static volatile String abortReason;
    private static long idxStartedAtNanos;
    private static long idxStartedAt = -1;

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
        abortReason = null;
        idxStartedAt = 0;
        idxStartedAtNanos = System.nanoTime();
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
            int objCount  = traceBuffer.getInt(pos + 20);
            int objCreatorRole = traceBuffer.getInt(pos + 24);
            int creatorRoles = traceBuffer.getInt(pos + 28);
            long objSite  = traceBuffer.getLong(pos + 32);
            long data1    = traceBuffer.getLong(pos + 40);
            long data2    = traceBuffer.getLong(pos + 48);
            long data3    = traceBuffer.getLong(pos + 56);
            long data4    = traceBuffer.getLong(pos + 64);
            long data5    = traceBuffer.getLong(pos + 72);
            long data6    = traceBuffer.getLong(pos + 80);

            if (seq == 0 && roleId == 0 && packedType == 0) continue;

            events.add(new long[] { seq, roleId, packedType, objSite, objCount,
                    data1, data2, data3, data4, i, objCreatorRole, creatorRoles, data5, data6 });
        }

        events.sort((a, b) -> Long.compare(a[0], b[0]));
        sortedEvents = events.toArray(new long[0][]);
        totalEvents  = sortedEvents.length;

        for (long[] event : sortedEvents) {
            pendingRoles.add((int) event[1]);
        }
    }

    private static void throwIfAborted() {
        String reason = abortReason;
        if (reason != null) {
            throw new ReplayDivergedException(reason);
        }
    }

    private static String eventSummary(long[] event) {
        return String.format("role=%d type=%d objSite=%d objCount=%d creator=%d data1=%d data2=%d",
                (int) event[1], (int) event[2] & 0xFF, event[3], (int) event[4],
                (int) event[10], event[5], event[6]);
    }

    private static String actualSummary(int roleId, int packedType, long objSite, int objCount,
                                        long data, int objCreatorRole) {
        return String.format("role=%d type=%d objSite=%d objCount=%d creator=%d data=%d",
                roleId, packedType & 0xFF, objSite, objCount, objCreatorRole, data);
    }

    private static void abortReplay(String reason) {
        if (abortReason == null) {
            abortReason = reason;
            System.err.println("[Replay DIVERGED] " + reason);
            for (Condition c : roleConditions.values()) {
                c.signalAll();
            }
            anyChange.signalAll();
        }
        throwIfAborted();
    }

    private static void noteIdxProgress(long idx) {
        if (idxStartedAt != idx) {
            idxStartedAt = idx;
            idxStartedAtNanos = System.nanoTime();
        }
    }

    private static void advanceIdxAndSignal() {
        long idx = currentIdx.incrementAndGet();
        noteIdxProgress(idx);
        signalNext();
    }

    private static void abortIfSameRoleDiverged(long idx, long[] expected,
                                                int roleId, int packedType,
                                                long objSite, int objCount, long data,
                                                int objCreatorRole) {
        if (roleId == (int) expected[1]) {
            abortReplay(String.format("idx=%d expected %s but replay reached %s",
                    idx, eventSummary(expected),
                    actualSummary(roleId, packedType, objSite, objCount, data, objCreatorRole)));
        }
    }

    private static void abortIfStalled(long idx, long[] expected,
                                       int roleId, int packedType,
                                       long objSite, int objCount, long data,
                                       int objCreatorRole) {
        if (STALL_TIMEOUT_NANOS <= 0) return;
        noteIdxProgress(idx);
        long elapsed = System.nanoTime() - idxStartedAtNanos;
        if (elapsed >= STALL_TIMEOUT_NANOS) {
            abortReplay(String.format(
                    "idx=%d made no progress for %d ms; expected %s; last waiter was %s",
                    idx, TimeUnit.NANOSECONDS.toMillis(elapsed), eventSummary(expected),
                    actualSummary(roleId, packedType, objSite, objCount, data, objCreatorRole)));
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

    /**
     * Returns the child role captured on the matching THREAD_START event.
     * Capture assigns child roles in TraceLogger.logSync(Thread.start) and stores
     * the child role in data1. Replay must reuse that role for the same Thread
     * object, not infer it from the earliest pending child event.
     */
    public static int childRoleForThreadStart(long objSite, int objCount, long siteId) {
        int threadStartType = BinarySchema.packType(BinarySchema.Event.THREAD_START, BinarySchema.Flags.NONE);
        controlLock.lock();
        try {
            throwIfAborted();
            long idx = currentIdx.get();
            int fallbackChildRole = -1;
            for (long i = idx; i < totalEvents; i++) {
                long[] event = sortedEvents[(int) i];
                if ((int) event[2] == threadStartType
                        && event[6] == siteId) {
                    if (fallbackChildRole == -1) {
                        fallbackChildRole = (int) event[5];
                    }
                    if (event[3] != objSite || (int) event[4] != objCount) {
                        continue;
                    }
                    int childRole = (int) event[5];
                    if (pendingRoles.contains(childRole) || startedRoles.contains(childRole) || activeRoles.contains(childRole)) {
                        return childRole;
                    }
                    return -1;
                }
            }
            if (fallbackChildRole != -1
                    && (pendingRoles.contains(fallbackChildRole)
                        || startedRoles.contains(fallbackChildRole)
                        || activeRoles.contains(fallbackChildRole))) {
                return fallbackChildRole;
            }
            return -1;
        } finally {
            controlLock.unlock();
        }
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
        } else if (pendingRoles.remove(roleId)) {
            activeRoles.add(roleId);
            signalNext();
        }
    }

    // ---- Payload matching helpers ----

    private static boolean payloadMatches(long[] expected, long data) {
        int eventId = (int) expected[2] & 0xFF;
        if (eventId == BinarySchema.Event.THREAD_START) {
            // data2 stores the call-site; match against it
            return (expected[6] == data);
        }
        if (eventId == BinarySchema.Event.MONITOR_ENTER
                || eventId == BinarySchema.Event.MONITOR_EXIT
                || eventId == BinarySchema.Event.THREAD_WAIT
                || eventId == BinarySchema.Event.THREAD_NOTIFY
                || eventId == BinarySchema.Event.THREAD_NOTIFY_ALL) {
            return true;
        }
        // General case: data1 is canonical; fall back to data2 for old traces where data1==0.
        return (expected[5] == data) || (expected[5] == 0 && expected[6] == data);
    }

    private static boolean matchesEvent(long[] expected, int roleId, int packedType,
                                        long objSite, int objCount, long data,
                                        int objCreatorRole) {
        int eventId = (int) expected[2] & 0xFF;
        boolean creatorMatches = objCreatorRole == Integer.MIN_VALUE
                || (int) expected[10] == objCreatorRole;
        if (eventId == BinarySchema.Event.THREAD_START) {
            return roleId == (int) expected[1]
                && packedType == (int) expected[2]
                && payloadMatches(expected, data);
        }
        if (eventId == BinarySchema.Event.CLASS_INIT_BEGIN
                || eventId == BinarySchema.Event.CLASS_INIT_END) {
            return packedType  == (int) expected[2]
                && objSite == expected[3]
                && objCount    == (int) expected[4]
                && creatorMatches
                && payloadMatches(expected, data);
        }
        // COLLECTION_OPs are used for many collection/iterator operations and
        // allocation identity can differ due to lazy registration of transient
        // view/iterator wrappers inside library code. The ordering signal we
        // care about is the role plus the collection-operation site payload.
        // Do not require object identity for COLLECTION_OP.
        if (eventId == BinarySchema.Event.COLLECTION_OP) {
            return roleId      == (int) expected[1]
            && packedType  == (int) expected[2]
            && payloadMatches(expected, data);
        }

        return roleId      == (int) expected[1]
            && packedType  == (int) expected[2]
            && objSite == expected[3]
            && objCount    == (int) expected[4]
            && creatorMatches
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
            noteIdxProgress(idx);
            long elapsed = System.nanoTime() - idxStartedAtNanos;
            if (STALL_TIMEOUT_NANOS > 0 && elapsed >= STALL_TIMEOUT_NANOS) {
                abortReplay(String.format("idx=%d made no progress for %d ms; expected %s but role=%d never started",
                        idx, TimeUnit.NANOSECONDS.toMillis(elapsed), eventSummary(expected), expectedRole));
            }
            anyChange.await(10, TimeUnit.MILLISECONDS);
            return true;
        }
        if (startedRoles.contains(expectedRole)) {
            anyChange.await(10, TimeUnit.MILLISECONDS);
            return true; // re-check
        }
        if (!activeRoles.contains(expectedRole)) {
            System.err.println("[Replay] role=" + expectedRole + " is dead, skipping idx=" + idx);
            skippedCount.incrementAndGet();
            advanceIdxAndSignal();
            return true;
        }
        return false; // role is active — caller decides
    }

    // ---- Public await API ----

    public static void awaitTurn(int roleId, int packedType, long objSite, int objCount, long data) {
        awaitTurn(roleId, packedType, objSite, objCount, data, null);
    }

    public static void awaitTurn(int roleId, int packedType, IdentityMapper.BirthId birthId, long data) {
        awaitTurn(roleId, packedType, birthId.siteId, birthId.count, data, null,
                IdentityMapper.getCreatorRoleId(birthId));
    }

    public static void awaitTurn(int roleId, int packedType, IdentityMapper.BirthId birthId,
                                 long data, Runnable onMatch) {
        awaitTurn(roleId, packedType, birthId.siteId, birthId.count, data, onMatch,
                IdentityMapper.getCreatorRoleId(birthId));
    }

    public static void awaitTurn(int roleId, int packedType, long objSite, int objCount,
                                 long data, int objCreatorRole) {
        awaitTurn(roleId, packedType, objSite, objCount, data, null, objCreatorRole);
    }

    /**
     * Blocks the calling thread until its event is next in the captured total order,
     * then runs {@code onMatch} (if any) atomically before advancing the index.
     */
    public static void awaitTurn(int roleId, int packedType, long objSite, int objCount, long data, Runnable onMatch) {
        awaitTurn(roleId, packedType, objSite, objCount, data, onMatch, IGNORE_CREATOR_ROLE);
    }

    public static void awaitTurn(int roleId, int packedType, long objSite, int objCount,
                                 long data, Runnable onMatch, int objCreatorRole) {
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            System.out.println(String.format("[awaitTurn] role=%d type=%d objSite=%d objCount=%d data=%d",
                    roleId, packedType & 0xFF, objSite, objCount, data));
            while (true) {
                throwIfAborted();
                long idx = currentIdx.get();
                if (idx >= totalEvents) return;
                noteIdxProgress(idx);

                long[] expected = sortedEvents[(int) idx];
                System.out.println(String.format("[check] idx=%-4d expects role=%d type=%d objSite=%d objCount=%d data1=%d data2=%d",
                    idx, (int) expected[1], (int) expected[2] & 0xFF, expected[3], expected[4], expected[5], expected[6]));

                if (advancePastBlockedRoles()) continue;

                if (matchesEvent(expected, roleId, packedType, objSite, objCount, data, objCreatorRole)) {
                    recordMatch(expected);
                    if (onMatch != null) {
                        try { onMatch.run(); } catch (Exception e) { e.printStackTrace(); }
                    }
                    advanceIdxAndSignal();
                    return;
                }

                // Field reads in spin loops can appear with different fieldIds between
                // capture and replay due to short-circuit evaluation differences (e.g.
                // `a && b` skips `b` when `a` is false, so which fields get read varies
                // with runtime values). When the same role presents a FIELD_READ at the
                // same object site but a different fieldId, skip the stale trace event
                // and re-anchor on the next synchronisation event.
                if (roleId == (int) expected[1]
                        && (expected[2] & 0xFF) == BinarySchema.Event.FIELD_READ
                        && (packedType  & 0xFF) == BinarySchema.Event.FIELD_READ
                        && objSite  == expected[3]
                        && objCount == (int) expected[4]) {
                    skippedCount.incrementAndGet();
                    advanceIdxAndSignal();
                    continue;
                }

                abortIfSameRoleDiverged(idx, expected, roleId, packedType, objSite, objCount, data, objCreatorRole);
                abortIfStalled(idx, expected, roleId, packedType, objSite, objCount, data, objCreatorRole);
                System.err.println(String.format("[WAIT]  idx=%-4d expects role=%d type=%d  got role=%d type=%d",
                        idx, (int) expected[1], (int) expected[2] & 0xFF, roleId, packedType & 0xFF));
                myTurn.await(100, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            controlLock.unlock();
        }
    }

    public static int awaitThreadStart(int roleId, IdentityMapper.BirthId birthId, long siteId) {
        int packedType = BinarySchema.packType(BinarySchema.Event.THREAD_START, BinarySchema.Flags.NONE);
        int objCreatorRole = IdentityMapper.getCreatorRoleId(birthId);
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            while (true) {
                throwIfAborted();
                long idx = currentIdx.get();
                if (idx >= totalEvents) return -1;
                noteIdxProgress(idx);

                long[] expected = sortedEvents[(int) idx];
                if (advancePastBlockedRoles()) continue;

                if (matchesEvent(expected, roleId, packedType, birthId.siteId, birthId.count,
                        siteId, objCreatorRole)) {
                    recordMatch(expected);
                    int childRole = (int) expected[5];
                    if (childRole != -1 && pendingRoles.remove(childRole)) {
                        startedRoles.add(childRole);
                    }
                    advanceIdxAndSignal();
                    return childRole;
                }

                abortIfSameRoleDiverged(idx, expected, roleId, packedType,
                        birthId.siteId, birthId.count, siteId, objCreatorRole);
                abortIfStalled(idx, expected, roleId, packedType,
                        birthId.siteId, birthId.count, siteId, objCreatorRole);
                myTurn.await(100, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            controlLock.unlock();
        }
    }

    public static int awaitTurnInt(int roleId, int packedType, long objSite, int objCount) {
        return awaitTurnInt(roleId, packedType, objSite, objCount, null);
    }

    public static int awaitTurnInt(int roleId, int packedType, IdentityMapper.BirthId birthId) {
        return awaitTurnInt(roleId, packedType, birthId.siteId, birthId.count, null,
                IdentityMapper.getCreatorRoleId(birthId));
    }

    public static int awaitTurnInt(int roleId, int packedType, long objSite, int objCount,
                                   int objCreatorRole) {
        return awaitTurnInt(roleId, packedType, objSite, objCount, null, objCreatorRole);
    }

    /**
     * Like awaitTurn, but returns data2 (the captured return value).
     * {@code onMatch} receives data1 (post-op value) for RMW write-back.
     */
    public static int awaitTurnInt(int roleId, int packedType, long objSite, int objCount,
                                   java.util.function.IntConsumer onMatch) {
        return awaitTurnInt(roleId, packedType, objSite, objCount, onMatch, IGNORE_CREATOR_ROLE);
    }

    public static int awaitTurnInt(int roleId, int packedType, long objSite, int objCount,
                                   java.util.function.IntConsumer onMatch, int objCreatorRole) {
        return awaitTurnInt(roleId, packedType, objSite, objCount, onMatch, objCreatorRole, IGNORE_AUX);
    }

    public static int awaitTurnInt(int roleId, int packedType, long objSite, int objCount,
                                   java.util.function.IntConsumer onMatch, int objCreatorRole,
                                   long data5) {
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            while (true) {
                throwIfAborted();
                long idx = currentIdx.get();
                if (idx >= totalEvents) return 0;
                noteIdxProgress(idx);
                long[] expected = sortedEvents[(int) idx];

                if (advancePastBlockedRoles()) continue;

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && objSite == expected[3] && objCount == (int) expected[4]
                        && (objCreatorRole == IGNORE_CREATOR_ROLE || objCreatorRole == (int) expected[10])
                        && (data5 == IGNORE_AUX || data5 == expected[12])) {
                    recordMatch(expected);
                    int postOpValue  = (int) expected[5];
                    int returnValue  = (int) expected[6];
                    if (onMatch != null) {
                        try { onMatch.accept(postOpValue); } catch (Exception e) { e.printStackTrace(); }
                    }
                    advanceIdxAndSignal();
                    return returnValue;
                }
                abortIfSameRoleDiverged(idx, expected, roleId, packedType, objSite, objCount, 0, objCreatorRole);
                abortIfStalled(idx, expected, roleId, packedType, objSite, objCount, 0, objCreatorRole);
                myTurn.await(100, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            controlLock.unlock();
        }
    }

    public static long awaitTurnLong(int roleId, int packedType, long objSite, int objCount) {
        return awaitTurnLong(roleId, packedType, objSite, objCount, null);
    }

    public static long awaitTurnLong(int roleId, int packedType, IdentityMapper.BirthId birthId) {
        return awaitTurnLong(roleId, packedType, birthId.siteId, birthId.count, null,
                IdentityMapper.getCreatorRoleId(birthId));
    }

    public static long awaitTurnLong(int roleId, int packedType, long objSite, int objCount,
                                     int objCreatorRole) {
        return awaitTurnLong(roleId, packedType, objSite, objCount, null, objCreatorRole);
    }

    /**
     * Like awaitTurnLong, but {@code onMatch} receives data3/data4 as the post-op cell value.
     * Returns data1/data2 (return value).
     */
    public static long awaitTurnLong(int roleId, int packedType, long objSite, int objCount,
                                     java.util.function.LongConsumer onMatch) {
        return awaitTurnLong(roleId, packedType, objSite, objCount, onMatch, IGNORE_CREATOR_ROLE);
    }

    public static long awaitTurnLong(int roleId, int packedType, long objSite, int objCount,
                                     java.util.function.LongConsumer onMatch, int objCreatorRole) {
        return awaitTurnLong(roleId, packedType, objSite, objCount, onMatch, objCreatorRole, IGNORE_AUX);
    }

    public static long awaitTurnLong(int roleId, int packedType, long objSite, int objCount,
                                     java.util.function.LongConsumer onMatch, int objCreatorRole,
                                     long data5) {
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            while (true) {
                throwIfAborted();
                long idx = currentIdx.get();
                if (idx >= totalEvents) return 0L;
                noteIdxProgress(idx);
                long[] expected = sortedEvents[(int) idx];

                if (advancePastBlockedRoles()) continue;

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && objSite == expected[3] && objCount == (int) expected[4]
                        && (objCreatorRole == IGNORE_CREATOR_ROLE || objCreatorRole == (int) expected[10])
                        && (data5 == IGNORE_AUX || data5 == expected[12])) {
                    recordMatch(expected);
                    long returnValue = expected[5];
                    if (onMatch != null) {
                        long postOpValue = expected[6];
                        try { onMatch.accept(postOpValue); } catch (Exception e) { e.printStackTrace(); }
                    }
                    advanceIdxAndSignal();
                    return returnValue;
                }
                abortIfSameRoleDiverged(idx, expected, roleId, packedType, objSite, objCount, 0, objCreatorRole);
                abortIfStalled(idx, expected, roleId, packedType, objSite, objCount, 0, objCreatorRole);
                myTurn.await(100, TimeUnit.MILLISECONDS);
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
    public static void beginAtomicReplay(int roleId, int packedType, long objSite, int objCount) {
        beginAtomicReplay(roleId, packedType, objSite, objCount, IGNORE_CREATOR_ROLE);
    }

    public static void beginAtomicReplay(int roleId, int packedType, long objSite, int objCount,
                                         int objCreatorRole) {
        beginAtomicReplay(roleId, packedType, objSite, objCount, objCreatorRole, IGNORE_AUX);
    }

    public static void beginAtomicReplay(int roleId, int packedType, long objSite, int objCount,
                                         int objCreatorRole, long data5) {
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            while (true) {
                throwIfAborted();
                long idx = currentIdx.get();
                if (idx >= totalEvents) return;
                noteIdxProgress(idx);
                long[] expected = sortedEvents[(int) idx];

                if (advancePastBlockedRoles()) continue;

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && objSite == expected[3] && objCount == (int) expected[4]
                        && (objCreatorRole == IGNORE_CREATOR_ROLE || objCreatorRole == (int) expected[10])
                        && (data5 == IGNORE_AUX || data5 == expected[12])) {
                    recordMatch(expected);
                    return; // lock still held; endAtomicReplay() will advance + release
                }
                abortIfSameRoleDiverged(idx, expected, roleId, packedType, objSite, objCount, 0, objCreatorRole);
                abortIfStalled(idx, expected, roleId, packedType, objSite, objCount, 0, objCreatorRole);
                System.err.println(String.format("[WAIT]  idx=%-4d expects role=%d type=%d  got role=%d type=%d",
                        idx, (int) expected[1], (int) expected[2] & 0xFF, roleId, packedType & 0xFF));
                myTurn.await(100, TimeUnit.MILLISECONDS);
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

    /**
     * Waits for a sync-like event including its site/data payload and keeps the
     * coordinator lock held so the caller can execute the native operation before
     * the trace advances.
     */
    public static void beginOrderedReplay(int roleId, int packedType,
                                          long objSite, int objCount, long data) {
        beginOrderedReplay(roleId, packedType, objSite, objCount, data, IGNORE_CREATOR_ROLE);
    }

    public static void beginOrderedReplay(int roleId, int packedType,
                                          long objSite, int objCount, long data,
                                          int objCreatorRole) {
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            while (true) {
                throwIfAborted();
                long idx = currentIdx.get();
                if (idx >= totalEvents) return;
                noteIdxProgress(idx);
                long[] expected = sortedEvents[(int) idx];

                if (advancePastBlockedRoles()) continue;

                if (matchesEvent(expected, roleId, packedType, objSite, objCount, data, objCreatorRole)) {
                    recordMatch(expected);
                    return;
                }
                abortIfSameRoleDiverged(idx, expected, roleId, packedType, objSite, objCount, data, objCreatorRole);
                abortIfStalled(idx, expected, roleId, packedType, objSite, objCount, data, objCreatorRole);
                myTurn.await(100, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            controlLock.unlock();
        } catch (RuntimeException | Error e) {
            controlLock.unlock();
            throw e;
        }
        // Lock intentionally still held on normal return.
    }

    /** Advances the total-order index and releases the controlLock held by {@link #beginAtomicReplay}. */
    public static void endAtomicReplay() {
        try {
            advanceIdxAndSignal();
        } finally {
            controlLock.unlock();
        }
    }

    public static Object awaitTurnObj(int roleId, int packedType, long objSite, int objCount) {
        return awaitTurnObj(roleId, packedType, objSite, objCount, null);
    }

    public static Object awaitTurnObj(int roleId, int packedType, IdentityMapper.BirthId birthId) {
        return awaitTurnObj(roleId, packedType, birthId.siteId, birthId.count, null,
                IdentityMapper.getCreatorRoleId(birthId));
    }

    /**
     * Like awaitTurnObj, but {@code onMatch} receives the post-op object for RMW write-back.
     * Returns the captured return value resolved from data1/data2.
     */
    public static Object awaitTurnObj(int roleId, int packedType, long objSite, int objCount,
                                      java.util.function.Consumer<Object> onMatch) {
        return awaitTurnObj(roleId, packedType, objSite, objCount, onMatch, IGNORE_CREATOR_ROLE);
    }

    public static Object awaitTurnObj(int roleId, int packedType, long objSite, int objCount,
                                      java.util.function.Consumer<Object> onMatch, int objCreatorRole) {
        return awaitTurnObj(roleId, packedType, objSite, objCount, onMatch, objCreatorRole, IGNORE_AUX);
    }

    public static Object awaitTurnObj(int roleId, int packedType, long objSite, int objCount,
                                      java.util.function.Consumer<Object> onMatch, int objCreatorRole,
                                      long data5) {
        controlLock.lock();
        try {
            activateRole(roleId);
            Condition myTurn = conditionFor(roleId);
            while (true) {
                throwIfAborted();
                long idx = currentIdx.get();
                if (idx >= totalEvents) return null;
                noteIdxProgress(idx);
                long[] expected = sortedEvents[(int) idx];

                if (advancePastBlockedRoles()) continue;

                if (roleId == (int) expected[1] && packedType == (int) expected[2]
                        && objSite == expected[3] && objCount == (int) expected[4]
                        && (objCreatorRole == IGNORE_CREATOR_ROLE || objCreatorRole == (int) expected[10])
                        && (data5 == IGNORE_AUX || data5 == expected[12])) {
                    recordMatch(expected);
                    long valueSiteId = expected[5];
                    int valueCount     = (int) expected[6];
                    int creatorRoles = (int) expected[11];
                    int valueCreator   = (creatorRoles >> 16) & 0xFFFF;
                    int postOpCreator  = creatorRoles & 0xFFFF;
                    Object returnValue = IdentityMapper.resolveByBirthId(valueCreator, valueSiteId, valueCount);
                    if (onMatch != null) {
                        long postOpSiteId = expected[7];
                        int postOpCount  = (int) expected[8];
                        Object postOpValue = IdentityMapper.resolveByBirthId(postOpCreator, postOpSiteId, postOpCount);
                        try { onMatch.accept(postOpValue); } catch (Exception e) { e.printStackTrace(); }
                    }
                    advanceIdxAndSignal();
                    return returnValue;
                }
                abortIfSameRoleDiverged(idx, expected, roleId, packedType, objSite, objCount, 0, objCreatorRole);
                abortIfStalled(idx, expected, roleId, packedType, objSite, objCount, 0, objCreatorRole);
                myTurn.await(100, TimeUnit.MILLISECONDS);
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
