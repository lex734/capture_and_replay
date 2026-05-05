package replay;

import common.BinarySchema;
import common.IdentityMapper;
import common.IdentityMapper.BirthId;
import common.TraceSemantics;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Replay-side counterpart to CaptureMonitor.
 *
 * Every method mirrors CaptureMonitor's signature exactly so that the
 * SyncTransformer can swap "capture/CaptureMonitor" → "replay/ReplayMonitor"
 * without changing any bytecode instrumentation logic.
 *
 * Replay gates only schedule boundaries. Non-boundary events execute naturally.
 */
public class ReplayMonitor {
    private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

    private static void awaitScheduleBoundary(int roleId, int packedType, int currentSiteId) {
        ReplayCoordinator.awaitScheduleTurn(roleId, packedType, currentSiteId);
    }

    // ---- Sync events (monitor enter/exit, thread lifecycle, wait/notify, park/unpark) ----

    public static void checkSync(int eventType, Object lock, int currentSiteId) {
        if (isInside.get()) return;
        if (lock == null && eventType != BinarySchema.Event.THREAD_PARK
            && eventType != BinarySchema.Event.THREAD_SLEEP
            && eventType != BinarySchema.Event.THREAD_WAKEUP
            && eventType != BinarySchema.Event.THREAD_YIELD
            && eventType != BinarySchema.Event.CLASS_INIT_BEGIN
            && eventType != BinarySchema.Event.CLASS_INIT_END) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            awaitScheduleBoundary(roleId, packedType, currentSiteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format(
                    "[CHECK-SYNC]  epoch=%d seq=%d role=%d  %-24s lock=%s  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    lock != null ? lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)) : "null",
                    currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void replayThreadStart(Thread thread, int currentSiteId) {
        if (thread == null) return;
        if (isInside.get()) {
            thread.start();
            return;
        }
        isInside.set(true);
        try {
            preRegisterThread(thread);
            thread.start();

            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            int packedType = BinarySchema.packType(BinarySchema.Event.THREAD_START, BinarySchema.Flags.NONE);
            awaitScheduleBoundary(roleId, packedType, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void replayLock(Lock lock, int currentSiteId) {
        if (isInside.get() || lock == null) {
            if (lock != null) lock.lock();
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                lock.lock();
                return;
            }
            int packedType = BinarySchema.packType(BinarySchema.Event.MONITOR_ENTER, BinarySchema.Flags.NONE);
            lock.lock();
            // Capture records MONITOR_ENTER after successful acquisition. Replay must do
            // the same so lock-dependent control flow (for example isLocked()) observes
            // natural ownership state before matching the event.
            awaitScheduleBoundary(roleId, packedType, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void replayUnlock(Lock lock, int currentSiteId) {
        if (isInside.get() || lock == null) {
            if (lock != null) {
                try {
                    lock.unlock();
                } catch (IllegalMonitorStateException ignored) {
                    // Replay-only ownership mismatch: swallow to avoid killing the thread.
                }
            }
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try {
                    lock.unlock();
                } catch (IllegalMonitorStateException ignored) {
                    // Role-unmapped thread; avoid replay-only crash.
                }
                return;
            }
            int packedType = BinarySchema.packType(BinarySchema.Event.MONITOR_EXIT, BinarySchema.Flags.NONE);
            if (lock instanceof ReentrantLock && !((ReentrantLock) lock).isHeldByCurrentThread()) {
                System.err.println("[DIVERGENCE] replayUnlock: current thread does not own lock; skipping unlock");
                return;
            }
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException e) {
                System.err.println("[DIVERGENCE] replayUnlock: unlock without ownership; skipping sync consume");
                return;
            }
            awaitScheduleBoundary(roleId, packedType, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static Condition replayNewCondition(Lock lock, int currentSiteId) {
        if (lock == null) return null;
        Condition condition = lock.newCondition();
        if (isInside.get() || condition == null) return condition;
        isInside.set(true);
        try {
            IdentityMapper.registerAllocation(condition, currentSiteId);
            return condition;
        } finally {
            isInside.set(false);
        }
    }

    private static void conditionAwaitTurn(Condition condition, int currentSiteId) {
        long tid = Thread.currentThread().getId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return;
        int packedType = BinarySchema.packType(BinarySchema.Event.THREAD_WAIT, BinarySchema.Flags.NONE);
        awaitScheduleBoundary(roleId, packedType, currentSiteId);
    }

    public static void replayAwait(Condition condition, int currentSiteId) throws InterruptedException {
        if (isInside.get() || condition == null) {
            if (condition != null) condition.await();
            return;
        }
        isInside.set(true);
        try {
            conditionAwaitTurn(condition, currentSiteId);
            condition.await();
        } finally {
            isInside.set(false);
        }
    }

    public static void replayAwaitUninterruptibly(Condition condition, int currentSiteId) {
        if (isInside.get() || condition == null) {
            if (condition != null) condition.awaitUninterruptibly();
            return;
        }
        isInside.set(true);
        try {
            conditionAwaitTurn(condition, currentSiteId);
            condition.awaitUninterruptibly();
        } finally {
            isInside.set(false);
        }
    }

    public static long replayAwaitNanos(Condition condition, long nanosTimeout, int currentSiteId)
            throws InterruptedException {
        if (isInside.get() || condition == null) {
            return condition != null ? condition.awaitNanos(nanosTimeout) : 0L;
        }
        isInside.set(true);
        try {
            conditionAwaitTurn(condition, currentSiteId);
            return condition.awaitNanos(nanosTimeout);
        } finally {
            isInside.set(false);
        }
    }

    public static boolean replayAwaitUntil(Condition condition, Date deadline, int currentSiteId)
            throws InterruptedException {
        if (isInside.get() || condition == null) {
            return condition != null && condition.awaitUntil(deadline);
        }
        isInside.set(true);
        try {
            conditionAwaitTurn(condition, currentSiteId);
            return condition.awaitUntil(deadline);
        } finally {
            isInside.set(false);
        }
    }

    public static boolean replayAwaitTimed(Condition condition, long time, TimeUnit unit, int currentSiteId)
            throws InterruptedException {
        if (isInside.get() || condition == null) {
            return condition != null && condition.await(time, unit);
        }
        isInside.set(true);
        try {
            conditionAwaitTurn(condition, currentSiteId);
            return condition.await(time, unit);
        } finally {
            isInside.set(false);
        }
    }

    public static void replaySignal(Condition condition, int currentSiteId) {
        if (isInside.get() || condition == null) {
            if (condition != null) condition.signal();
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            condition.signal();
            if (roleId != -1) {
                int packedType = BinarySchema.packType(BinarySchema.Event.THREAD_NOTIFY, BinarySchema.Flags.NONE);
                awaitScheduleBoundary(roleId, packedType, currentSiteId);
            }
        } finally {
            isInside.set(false);
        }
    }

    public static void replaySignalAll(Condition condition, int currentSiteId) {
        if (isInside.get() || condition == null) {
            if (condition != null) condition.signalAll();
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            condition.signalAll();
            if (roleId != -1) {
                int packedType = BinarySchema.packType(BinarySchema.Event.THREAD_NOTIFY_ALL, BinarySchema.Flags.NONE);
                awaitScheduleBoundary(roleId, packedType, currentSiteId);
            }
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field access events ----
    // Only replay-boundary field events (currently volatile writes) are
    // coordinated. All other field accesses execute naturally.

    private static int resolveFieldRole(Object owner, String ownerName, int currentSiteId,
            boolean isVolatile, boolean isStatic, int eventType,
            int[] outPacked, int[] outSite, int[] outCount) {
        long tid = Thread.currentThread().getId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return -1;
        BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
        int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                  | (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
        outPacked[0] = BinarySchema.packType(eventType, flags);
        outSite[0]   = birthId.siteId;
        outCount[0]  = birthId.count;
        return roleId;
    }

    public static int checkFieldInt(int naturalValue, int eventType, Object owner, int currentSiteId,
            boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            int[] packed = new int[1], site = new int[1], count = new int[1];
            int roleId = resolveFieldRole(owner, ownerName, currentSiteId,
                    isVolatile, isStatic, eventType, packed, site, count);
            if (roleId == -1) return naturalValue;
            if (TraceSemantics.isReplayBoundary(packed[0])) {
                awaitScheduleBoundary(roleId, packed[0], currentSiteId);
                return naturalValue;
            }
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static long checkFieldLong(long naturalValue, int eventType, Object owner, int currentSiteId,
            boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            int[] packed = new int[1], site = new int[1], count = new int[1];
            int roleId = resolveFieldRole(owner, ownerName, currentSiteId,
                    isVolatile, isStatic, eventType, packed, site, count);
            if (roleId == -1) return naturalValue;
            if (TraceSemantics.isReplayBoundary(packed[0])) {
                awaitScheduleBoundary(roleId, packed[0], currentSiteId);
                return naturalValue;
            }
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkFieldObj(Object naturalValue, int eventType, Object owner, int currentSiteId,
            boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            int[] packed = new int[1], site = new int[1], count = new int[1];
            int roleId = resolveFieldRole(owner, ownerName, currentSiteId,
                    isVolatile, isStatic, eventType, packed, site, count);
            if (roleId == -1) return naturalValue;
            if (TraceSemantics.isReplayBoundary(packed[0])) {
                awaitScheduleBoundary(roleId, packed[0], currentSiteId);
                return naturalValue;
            }
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array access events ----
    // Array accesses are not replay boundaries in schedule replay, so they always
    // execute naturally.

    public static int checkArrayInt(int naturalValue, int eventType, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return naturalValue;
        isInside.set(true);
        try {
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static long checkArrayLong(long naturalValue, int eventType, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return naturalValue;
        isInside.set(true);
        try {
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkArrayObj(Object naturalValue, int eventType, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return naturalValue;
        isInside.set(true);
        try {
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkArrayObjNoInject(Object naturalValue, int eventType, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return naturalValue;
        isInside.set(true);
        try {
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    // ---- Atomic operations ----

    private static int buildAtomicPackedType(int eventType, BirthId receiverBirth, int index) {
        if (index >= 0) {
            return (eventType & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8)
                    | ((receiverBirth.siteId & 0xFFFF) << 16);
        }
        return BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
    }

    private static int atomicObjSite(BirthId b, int index) { return index >= 0 ? b.count   : b.siteId; }
    private static int atomicObjCount(BirthId b, int index) { return index >= 0 ? index     : b.count;  }

    // ---- Atomic schedule checks ----
    // Atomic operations execute naturally. Replay only coordinates schedule
    // boundaries; it does not inject captured values.

    public static int checkAtomicInt(int naturalValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            if (TraceSemantics.isReplayBoundaryForEventType(eventType)) {
                BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
                awaitScheduleBoundary(roleId, buildAtomicPackedType(eventType, b, index), currentSiteId);
            }
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static long checkAtomicLong(long naturalValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            if (TraceSemantics.isReplayBoundaryForEventType(eventType)) {
                BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
                awaitScheduleBoundary(roleId, buildAtomicPackedType(eventType, b, index), currentSiteId);
            }
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkAtomicObj(Object naturalValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            if (TraceSemantics.isReplayBoundaryForEventType(eventType)) {
                BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
                awaitScheduleBoundary(roleId, buildAtomicPackedType(eventType, b, index), currentSiteId);
            }
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static int checkAtomicRmwInt(int naturalValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            if (TraceSemantics.isReplayBoundaryForEventType(eventType)) {
                BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
                awaitScheduleBoundary(roleId, buildAtomicPackedType(eventType, b, index), currentSiteId);
            }
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static long checkAtomicRmwLong(long naturalValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            if (TraceSemantics.isReplayBoundaryForEventType(eventType)) {
                BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
                awaitScheduleBoundary(roleId, buildAtomicPackedType(eventType, b, index), currentSiteId);
            }
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkAtomicRmwObj(Object naturalValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            if (TraceSemantics.isReplayBoundaryForEventType(eventType)) {
                BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
                awaitScheduleBoundary(roleId, buildAtomicPackedType(eventType, b, index), currentSiteId);
            }
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    public static void checkException(Object exception, int siteId) {
        // Exception throws are not replay boundaries in schedule replay.
    }

    // ---- Legacy nondeterministic replay helpers ----
    // Schedule replay no longer injects captured values for nondeterministic calls.
    // These wrappers remain only for compatibility with older instrumentation and
    // should not be reached once replay bytecode executes the original call.

    public static int replayNondetInt(int siteId) {
        if (isInside.get()) return 0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return 0;
            int packedType = BinarySchema.packType(BinarySchema.Event.NONDETERMINISTIC_INT, BinarySchema.Flags.NONE);
            int val = ReplayCoordinator.awaitTurnInt(roleId, packedType, 0, siteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format(
                    "[REPLAY-NONDET] epoch=%d seq=%d role=%d  nondet_int=%d  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, val, siteId));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static float replayNondetFloat(int siteId) {
        if (isInside.get()) return 0f;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return 0f;
            int packedType = BinarySchema.packType(BinarySchema.Event.NONDETERMINISTIC_INT, BinarySchema.Flags.NONE);
            int bits = ReplayCoordinator.awaitTurnInt(roleId, packedType, 0, siteId);
            float val = Float.intBitsToFloat(bits);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format(
                    "[REPLAY-NONDET] epoch=%d seq=%d role=%d  nondet_float=%f  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, val, siteId));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static long replayNondetLong(int siteId) {
        if (isInside.get()) return 0L;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return 0L;
            int packedType = BinarySchema.packType(BinarySchema.Event.NONDETERMINISTIC_LONG, BinarySchema.Flags.NONE);
            long val = ReplayCoordinator.awaitTurnLong(roleId, packedType, 0, siteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format(
                    "[REPLAY-NONDET] epoch=%d seq=%d role=%d  nondet_long=%d  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, val, siteId));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static double replayNondetDouble(int siteId) {
        if (isInside.get()) return 0.0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return 0.0;
            int packedType = BinarySchema.packType(BinarySchema.Event.NONDETERMINISTIC_LONG, BinarySchema.Flags.NONE);
            long bits = ReplayCoordinator.awaitTurnLong(roleId, packedType, 0, siteId);
            double val = Double.longBitsToDouble(bits);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format(
                    "[REPLAY-NONDET] epoch=%d seq=%d role=%d  nondet_double=%f  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, val, siteId));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static void preRegisterThread(Thread thread) {
        if (thread == null) return;
        // Assign this thread's roleId now, before it runs a single instruction.
        // Look up the child role from the parent's next THREAD_START event in the
        // trace (data1 holds the childRoleId written at capture time). This is
        // correct even when threads run different workloads, because the mapping is
        // based on which parent spawned which child, not on global sequence order.
        long tid = thread.getId();
        int parentRole = IdentityMapper.getRoleId(Thread.currentThread().getId());
        int nextRole = (parentRole != -1)
                ? ReplayCoordinator.peekChildRoleFromThreadStart(parentRole)
                : -1;
        if (nextRole == -1) {
            // Fallback: no THREAD_START found in parent queue — use sequence order.
            nextRole = ReplayCoordinator.peekNextPendingRole();
        }
        if (nextRole == -1) return;

        IdentityMapper.preAssignRole(tid, nextRole);
        // Do NOT call checkIn() here. checkIn() would place the child role in
        // startedRoles before thread.start() is actually called. isAnyRoleBehind
        // checks startedRoles and would block the parent's THREAD_START epoch
        // advancement if the child has any trace events at epochs below the
        // THREAD_START epoch (which happens when the child races ahead of the
        // parent's logSync call during capture). Since thread.start() is called
        // AFTER checkSync(THREAD_START) returns, the child thread can never run
        // to process those events — a true deadlock. Instead, the child remains
        // in pendingRoles (invisible to the epoch guard) until its thread actually
        // starts and calls awaitTurn(), at which point activateRole() moves it
        // directly from pendingRoles to activeRoles.
        debug("[PreRegister] role=" + nextRole + " (parent role=" + parentRole + ")");
    }

    private static void debug(String message) {
        // System.out.println(message);
    }

    private static String getEventName(int eventType) {
        switch (eventType) {
            case BinarySchema.Event.MONITOR_ENTER:        return "MONITOR_ENTER";
            case BinarySchema.Event.MONITOR_EXIT:         return "MONITOR_EXIT";
            case BinarySchema.Event.THREAD_PARK:          return "THREAD_PARK";
            case BinarySchema.Event.THREAD_UNPARK:        return "THREAD_UNPARK";
            case BinarySchema.Event.THREAD_START:         return "THREAD_START";
            case BinarySchema.Event.THREAD_JOIN:          return "THREAD_JOIN";
            case BinarySchema.Event.THREAD_INTERRUPT:     return "THREAD_INTERRUPT";
            case BinarySchema.Event.THREAD_SLEEP:         return "THREAD_SLEEP";
            case BinarySchema.Event.THREAD_WAKEUP:        return "THREAD_WAKEUP";
            case BinarySchema.Event.THREAD_YIELD:         return "THREAD_YIELD";
            case BinarySchema.Event.THREAD_WAIT:          return "THREAD_WAIT";
            case BinarySchema.Event.THREAD_NOTIFY:        return "THREAD_NOTIFY";
            case BinarySchema.Event.THREAD_NOTIFY_ALL:    return "THREAD_NOTIFY_ALL";
            case BinarySchema.Event.THREAD_JOIN_TIMEOUT:  return "THREAD_JOIN_TIMEOUT";
            case BinarySchema.Event.THREAD_INTERRUPT_CHECK: return "THREAD_INTERRUPT_CHECK";
            case BinarySchema.Event.ATOMIC_READ:          return "ATOMIC_READ";
            case BinarySchema.Event.ATOMIC_WRITE:         return "ATOMIC_WRITE";
            case BinarySchema.Event.ATOMIC_RMW:           return "ATOMIC_RMW";
            case BinarySchema.Event.ATOMIC_CAS:           return "ATOMIC_CAS";
            case BinarySchema.Event.CLASS_INIT_BEGIN:     return "CLASS_INIT_BEGIN";
            case BinarySchema.Event.CLASS_INIT_END:       return "CLASS_INIT_END";
            case BinarySchema.Event.EXCEPTION_THROW:        return "EXCEPTION_THROW";
            case BinarySchema.Event.NONDETERMINISTIC_INT:  return "NONDETERMINISTIC_INT";
            case BinarySchema.Event.NONDETERMINISTIC_LONG: return "NONDETERMINISTIC_LONG";
            default:                                       return "UNKNOWN(" + eventType + ")";
        }
    }
}
