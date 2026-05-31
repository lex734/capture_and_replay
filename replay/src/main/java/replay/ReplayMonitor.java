package replay;

import common.BinarySchema;
import common.FieldKey;
import common.IdentityMapper;
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
 * Instead of logging to a trace file, each method resolves the same identity
 * values (roleId, birthId, siteId, fieldId, …) via IdentityMapper and then
 * blocks on ReplayCoordinator.awaitTurn until this event is next in the
 * captured total order.
 */
public class ReplayMonitor {
    private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

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
            ReplayCoordinator.awaitTurn(roleId, packedType, 0, 0, lock, currentSiteId);
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

    public static void beforeMonitorEnter(Object lock, int currentSiteId) {
        if (isInside.get() || lock == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;
            int packedType = BinarySchema.packType(BinarySchema.Event.MONITOR_ENTER, BinarySchema.Flags.NONE);
            ReplayCoordinator.prepareSyncTurn(roleId, packedType, 0, 0, lock, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void afterMonitorEnter(Object lock, int currentSiteId) {
        if (isInside.get() || lock == null) return;
        isInside.set(true);
        try {
            ReplayCoordinator.completePreparedSyncTurn();
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format(
                    "[CHECK-SYNC]  epoch=%d seq=%d role=%d  %-24s lock=%s  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(BinarySchema.Event.MONITOR_ENTER),
                    lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)),
                    currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void beforeMonitorExit(Object lock, int currentSiteId) {
        if (isInside.get() || lock == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;
            int packedType = BinarySchema.packType(BinarySchema.Event.MONITOR_EXIT, BinarySchema.Flags.NONE);
            ReplayCoordinator.prepareSyncTurn(roleId, packedType, 0, 0, lock, currentSiteId);
            ReplayCoordinator.completePreparedSyncTurn();
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format(
                    "[CHECK-SYNC]  epoch=%d seq=%d role=%d  %-24s lock=%s  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(BinarySchema.Event.MONITOR_EXIT),
                    lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)),
                    currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void afterMonitorExit(Object lock, int currentSiteId) {
        // Explicit MONITOREXIT is coordinated before the actual JVM release.
        // Capture records the event post-release; replay decides the exit first and
        // then lets the opcode perform the real monitor release immediately after.
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
            if (roleId == -1) {
                return;
            }

            int packedType = BinarySchema.packType(BinarySchema.Event.THREAD_START, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, packedType, 0, 0, thread, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void replayThreadJoin(Thread thread, int joinSiteId, int wakeupSiteId) throws InterruptedException {
        if (thread == null) return;
        if (isInside.get()) {
            thread.join();
            return;
        }
        isInside.set(true);
        try {
            thread.join();

            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, joinSiteId);
            if (roleId == -1) return;

            int joinPackedType = BinarySchema.packType(BinarySchema.Event.THREAD_JOIN, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, joinPackedType, 0, 0, thread, joinSiteId);
            int wakePackedType = BinarySchema.packType(BinarySchema.Event.THREAD_WAKEUP, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, wakePackedType, 0, 0, null, wakeupSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void replayThreadJoinTimed(Thread thread, long millis, int joinSiteId, int wakeupSiteId)
            throws InterruptedException {
        if (thread == null) return;
        if (isInside.get()) {
            thread.join(millis);
            return;
        }
        isInside.set(true);
        try {
            thread.join(millis);

            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, joinSiteId);
            if (roleId == -1) return;

            int joinPackedType = BinarySchema.packType(BinarySchema.Event.THREAD_JOIN_TIMEOUT, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, joinPackedType, 0, 0, thread, joinSiteId);
            int wakePackedType = BinarySchema.packType(BinarySchema.Event.THREAD_WAKEUP, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, wakePackedType, 0, 0, null, wakeupSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void replayThreadJoinTimedNanos(Thread thread, long millis, int nanos, int joinSiteId, int wakeupSiteId)
            throws InterruptedException {
        if (thread == null) return;
        if (isInside.get()) {
            thread.join(millis, nanos);
            return;
        }
        isInside.set(true);
        try {
            thread.join(millis, nanos);

            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, joinSiteId);
            if (roleId == -1) return;

            int joinPackedType = BinarySchema.packType(BinarySchema.Event.THREAD_JOIN_TIMEOUT, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, joinPackedType, 0, 0, thread, joinSiteId);
            int wakePackedType = BinarySchema.packType(BinarySchema.Event.THREAD_WAKEUP, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, wakePackedType, 0, 0, null, wakeupSiteId);
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
            int[] traceId = boundTraceId(lock);
            ReplayCoordinator.awaitTurn(roleId, packedType, traceId[0], traceId[1], lock, currentSiteId);
            // For wrapper-based ReentrantLock replay, coordinate before the real
            // acquire so a later thread cannot block inside replay while already
            // holding the JVM lock.
            lock.lock();
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
            // Consume the MONITOR_EXIT coordinator event (releasing the epoch) before
            // calling lock.unlock().  This matches the corrected capture order where
            // the epoch is incremented while the lock is still held, ensuring any
            // thread that subsequently acquires the lock sees the updated epoch.
            int[] traceId = boundTraceId(lock);
            ReplayCoordinator.awaitTurn(roleId, packedType, traceId[0], traceId[1], lock, currentSiteId);
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException e) {
                System.err.println("[DIVERGENCE] replayUnlock: unlock without ownership; skipping sync consume");
            }
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
        int[] traceId = boundTraceId(condition);
        ReplayCoordinator.awaitTurn(roleId, packedType, traceId[0], traceId[1], condition, currentSiteId);
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
                int[] traceId = boundTraceId(condition);
                ReplayCoordinator.awaitTurn(roleId, packedType, traceId[0], traceId[1], condition, currentSiteId);
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
                int[] traceId = boundTraceId(condition);
                ReplayCoordinator.awaitTurn(roleId, packedType, traceId[0], traceId[1], condition, currentSiteId);
            }
        } finally {
            isInside.set(false);
        }
    }

    private static int[] boundTraceId(Object runtimeObject) {
        long traceId = IdentityMapper.lookupTraceIdForObject(runtimeObject);
        if (traceId == Long.MIN_VALUE) {
            return new int[] { 0, 0 };
        }
        return new int[] { (int) (traceId >>> 32), (int) traceId };
    }

    // ---- Field access events ----
    // Each method blocks until the event's turn in the total order, then returns
    // the value that was recorded during capture.  The SyncTransformer uses the
    // returned value as the actual field value (for reads) or the value to write
    // (for writes), eliminating ordering dependence between reads and writes.

    private static int resolveFieldRole(Object owner, String ownerName, int currentSiteId,
            boolean isVolatile, boolean isStatic, boolean isObjectValue, int eventType,
            int[] outPacked, int[] outSite, int[] outCount) {
        long tid = Thread.currentThread().getId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return -1;
        int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                  | (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0)
                  | (isObjectValue ? BinarySchema.Flags.IS_OBJECT_VALUE : 0);
        outPacked[0] = BinarySchema.packType(eventType, flags);
        outSite[0]   = 0;
        outCount[0]  = 0;
        return roleId;
    }

    private static FieldKey fieldKey(String ownerName, String fieldName, String descriptor) {
        return FieldKey.of(ownerName, fieldName, descriptor);
    }

    public static int checkFieldInt(int naturalValue, int eventType, Object owner, int currentSiteId,
            boolean isVolatile, boolean isStatic, String fieldName, String ownerName, String descriptor) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            int[] packed = new int[1], site = new int[1], count = new int[1];
            int roleId = resolveFieldRole(owner, ownerName, currentSiteId,
                    isVolatile, isStatic, false, eventType, packed, site, count);
            if (roleId == -1) return naturalValue;
            int val = ReplayCoordinator.awaitTurnFieldInt(roleId, packed[0], site[0], count[0], owner,
                    fieldKey(ownerName, fieldName, descriptor), naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";
            debug(String.format("[CHECK-FIELD] epoch=%d seq=%d role=%d  %-5s%s %s.%s = %d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    isVolatile ? "(volatile)" : "", ownerName, fieldName, val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static long checkFieldLong(long naturalValue, int eventType, Object owner, int currentSiteId,
            boolean isVolatile, boolean isStatic, String fieldName, String ownerName, String descriptor) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            int[] packed = new int[1], site = new int[1], count = new int[1];
            int roleId = resolveFieldRole(owner, ownerName, currentSiteId,
                    isVolatile, isStatic, false, eventType, packed, site, count);
            if (roleId == -1) return naturalValue;
            long val = ReplayCoordinator.awaitTurnFieldLong(roleId, packed[0], site[0], count[0], owner,
                    fieldKey(ownerName, fieldName, descriptor), naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";
            debug(String.format("[CHECK-FIELD] epoch=%d seq=%d role=%d  %-5s%s %s.%s = %dL",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    isVolatile ? "(volatile)" : "", ownerName, fieldName, val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkFieldObj(Object naturalValue, int eventType, Object owner, int currentSiteId,
            boolean isVolatile, boolean isStatic, String fieldName, String ownerName, String descriptor) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            int[] packed = new int[1], site = new int[1], count = new int[1];
            int roleId = resolveFieldRole(owner, ownerName, currentSiteId,
                    isVolatile, isStatic, true, eventType, packed, site, count);
            if (roleId == -1) return naturalValue;
            Object val = ReplayCoordinator.awaitTurnFieldObj(roleId, packed[0], site[0], count[0], owner,
                    fieldKey(ownerName, fieldName, descriptor), naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";
            debug(String.format("[CHECK-FIELD] epoch=%d seq=%d role=%d  %-5s%s %s.%s = %s",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    isVolatile ? "(volatile)" : "", ownerName, fieldName,
                    val != null ? val.getClass().getSimpleName()
                            + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array access events ----
    // Mirror of field methods: block on total order, return captured element value.

    public static int checkArrayInt(int naturalValue, int eventType, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            int packedType = (eventType & 0xFF)
                    | (BinarySchema.Flags.IS_ARRAY_VALUED << 8)
                    | (0 << 16);
            int val = ReplayCoordinator.awaitTurnArrayInt(roleId, packedType, 0, index, array, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";
            debug(String.format("[CHECK-ARRAY] epoch=%d seq=%d role=%d  %-12s %s[%d] = %d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    index, val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static long checkArrayLong(long naturalValue, int eventType, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            int packedType = (eventType & 0xFF)
                    | (BinarySchema.Flags.IS_ARRAY_VALUED << 8)
                    | (0 << 16);
            long val = ReplayCoordinator.awaitTurnArrayLong(roleId, packedType, 0, index, array, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";
            debug(String.format("[CHECK-ARRAY] epoch=%d seq=%d role=%d  %-12s %s[%d] = %dL",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    index, val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkArrayObj(Object naturalValue, int eventType, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            int packedType = (eventType & 0xFF)
                    | ((BinarySchema.Flags.IS_ARRAY_VALUED | BinarySchema.Flags.IS_OBJECT_VALUE) << 8)
                    | (0 << 16);
            Object val = ReplayCoordinator.awaitTurnArrayObj(roleId, packedType, 0, index, array, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";
            debug(String.format("[CHECK-ARRAY] epoch=%d seq=%d role=%d  %-12s %s[%d] = %s",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    index,
                    val != null ? val.getClass().getSimpleName()
                            + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkArrayObjNoInject(Object naturalValue, int eventType, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            int packedType = (eventType & 0xFF)
                    | ((BinarySchema.Flags.IS_ARRAY_VALUED | BinarySchema.Flags.IS_OBJECT_VALUE) << 8)
                    | (0 << 16);
            Object traceVal = ReplayCoordinator.awaitTurnArrayObj(roleId, packedType, 0, index, array, naturalValue);
            if (traceVal != naturalValue) {
                ReplayCoordinator.recordNoInjectDisagreement();
            }
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";
            debug(String.format("[CHECK-ARRAY] epoch=%d seq=%d role=%d  %-12s %s[%d] = %s (natural)",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    index,
                    naturalValue != null ? naturalValue.getClass().getSimpleName()
                            + "@" + Integer.toHexString(System.identityHashCode(naturalValue)) : "null"));
            return naturalValue;
        } finally {
            isInside.set(false);
        }
    }

    // ---- Atomic operations ----

    private static int buildAtomicPackedType(int eventType, int index, boolean isObjectValue) {
        if (index >= 0) {
            int flags = BinarySchema.Flags.IS_ARRAY_ATOMIC
                    | (isObjectValue ? BinarySchema.Flags.IS_OBJECT_VALUE : 0);
            return (eventType & 0xFF) | (flags << 8)
                    | (0 << 16);
        }
        return BinarySchema.packType(eventType,
                isObjectValue ? BinarySchema.Flags.IS_OBJECT_VALUE : BinarySchema.Flags.NONE);
    }

    private static int atomicObjSite(int index) { return 0; }
    private static int atomicObjCount(int index) { return index >= 0 ? index : 0; }

    // ---- CAS injection ----
    // CAS outcomes are non-deterministic across schedules; replay injects the
    // captured result and records degraded replay when that substitution is used.

    public static int injectAtomicCasInt(Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return 0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return 0;
            int val = ReplayCoordinator.awaitTurnCasInt(roleId,
                    buildAtomicPackedType(eventType, index, false), atomicObjSite(index), atomicObjCount(index),
                    receiver);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format("[INJECT-CAS]  epoch=%d seq=%d role=%d  %-12s %s → %d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static long injectAtomicCasLong(Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return 0L;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return 0L;
            long val = ReplayCoordinator.awaitTurnCasLong(roleId,
                    buildAtomicPackedType(eventType, index, false), atomicObjSite(index), atomicObjCount(index),
                    receiver);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format("[INJECT-CAS]  epoch=%d seq=%d role=%d  %-12s %s → %dL",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static Object injectAtomicCasObj(Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return null;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return null;
            Object val = ReplayCoordinator.awaitTurnCasObj(roleId,
                    buildAtomicPackedType(eventType, index, true), atomicObjSite(index), atomicObjCount(index),
                    receiver);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format("[INJECT-CAS]  epoch=%d seq=%d role=%d  %-12s %s → %s",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val != null ? val.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    // ---- RMW divergence check ----
    // The atomic operation has already executed; natural value is always returned.
    // Divergence is logged but not injected (can't undo the completed operation).

    // ---- Deterministic atomic read/write check ----
    // For plain atomic reads/writes (get/set), compare against trace and inject the
    // captured value on read-side mismatches, recording degraded replay.

    public static int checkAtomicInt(int naturalValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            int val = ReplayCoordinator.awaitTurnFieldInt(roleId,
                    buildAtomicPackedType(eventType, index, false), atomicObjSite(index), atomicObjCount(index),
                    receiver, null, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format("[CHECK-ATOM]  epoch=%d seq=%d role=%d  %-12s %s = %d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val));
            return val;
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
            long val = ReplayCoordinator.awaitTurnFieldLong(roleId,
                    buildAtomicPackedType(eventType, index, false), atomicObjSite(index), atomicObjCount(index),
                    receiver, null, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format("[CHECK-ATOM]  epoch=%d seq=%d role=%d  %-12s %s = %dL",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val));
            return val;
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
            Object val = ReplayCoordinator.awaitTurnFieldObj(roleId,
                    buildAtomicPackedType(eventType, index, true), atomicObjSite(index), atomicObjCount(index),
                    receiver, null, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format("[CHECK-ATOM]  epoch=%d seq=%d role=%d  %-12s %s = %s",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val != null ? val.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
            return val;
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
            int val = ReplayCoordinator.awaitTurnRmwInt(roleId,
                    buildAtomicPackedType(eventType, index, false), atomicObjSite(index), atomicObjCount(index),
                    receiver, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format("[CHECK-RMW]   epoch=%d seq=%d role=%d  %-12s %s = %d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val));
            return val;
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
            long val = ReplayCoordinator.awaitTurnRmwLong(roleId,
                    buildAtomicPackedType(eventType, index, false), atomicObjSite(index), atomicObjCount(index),
                    receiver, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format("[CHECK-RMW]   epoch=%d seq=%d role=%d  %-12s %s = %dL",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val));
            return val;
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
            Object val = ReplayCoordinator.awaitTurnRmwObj(roleId,
                    buildAtomicPackedType(eventType, index, true), atomicObjSite(index), atomicObjCount(index),
                    receiver, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format("[CHECK-RMW]   epoch=%d seq=%d role=%d  %-12s %s = %s",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val != null ? val.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static void checkException(Object exception, int siteId) {
        if (isInside.get()) return;
        if (exception == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return;

            int packedType = BinarySchema.packType(BinarySchema.Event.EXCEPTION_THROW, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, packedType, 0, 0, exception, siteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            debug(String.format(
                    "[CHECK-THROW] epoch=%d seq=%d role=%d  %s  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId,
                    exception.getClass().getName(), siteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Nondeterministic value injection ----
    // These events are not object-based. Replay returns the captured value and
    // records degraded replay if that differs from the natural runtime value.
    //
    // In the current trace format the nondeterministic source key is encoded as:
    //   objSite  = 0
    //   objCount = nondeterministic call-site key
    //
    // This is just slot reuse in the V0/V1 transitional binary format, not
    // replay object identity.

    public static int replayNondetInt(int siteId) {
        if (isInside.get()) return 0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return 0;
            int packedType = BinarySchema.packType(BinarySchema.Event.NONDETERMINISTIC_INT, BinarySchema.Flags.NONE);
            int val = ReplayCoordinator.awaitTurnInt(roleId, packedType, 0, siteId, null);
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
            int bits = ReplayCoordinator.awaitTurnInt(roleId, packedType, 0, siteId, null);
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
            long val = ReplayCoordinator.awaitTurnLong(roleId, packedType, 0, siteId, null);
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
            long bits = ReplayCoordinator.awaitTurnLong(roleId, packedType, 0, siteId, null);
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
        if (Boolean.getBoolean("tool.trace.debug")) {
            System.err.println(message);
        }
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
