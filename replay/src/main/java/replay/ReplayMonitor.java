package replay;

import common.BinarySchema;
import common.IdentityMapper;
import common.IdentityMapper.BirthId;

import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

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

    private static void awaitSyncTurn(int eventType, Object lock, long currentSiteId) {
        long tid = Thread.currentThread().getId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return;

        BirthId birthId = IdentityMapper.getBirthId(lock, null, currentSiteId);
        int packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
        ReplayCoordinator.awaitTurn(roleId, packedType, birthId, currentSiteId);
    }

    public static void checkSync(int eventType, Object lock, long currentSiteId) {
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

            BirthId birthId = IdentityMapper.getBirthId(lock, null, currentSiteId);
            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, currentSiteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-SYNC]  seq=%d role=%d  %-24s lock=%s  site=%d",
                    // seq, roleId, getEventName(eventType),
                    // lock != null ? lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)) : "null",
                    // currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkThreadStart(Object thread, long currentSiteId) {
        if (!(thread instanceof Thread)) return;
        Thread childThread = (Thread) thread;
        if (IdentityMapper.shouldSkipThread(childThread)) return;
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(childThread, null, currentSiteId);
            int childRole = ReplayCoordinator.awaitThreadStart(roleId, birthId, currentSiteId);
            if (childRole != -1) {
                IdentityMapper.preAssignRole(childThread.getId(), childRole);
            }
        } finally {
            isInside.set(false);
        }
    }

    private static boolean beginCollectionReplay(Object collection, long currentSiteId) {
        if (isInside.get()) return false;
        isInside.set(true);
        long tid = Thread.currentThread().getId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) {
            isInside.set(false);
            return false;
        }

        BirthId birthId = IdentityMapper.getBirthId(collection, null, currentSiteId);
        int packedType = BinarySchema.packType(BinarySchema.Event.COLLECTION_OP, BinarySchema.Flags.NONE);
        ReplayCoordinator.beginOrderedReplay(roleId, packedType, birthId.siteId, birthId.count,
                currentSiteId, IdentityMapper.getCreatorRoleId(birthId));
        return true;
    }

    public static int vectorSize(java.util.Vector<?> vector, long currentSiteId) {
        boolean ordered = beginCollectionReplay(vector, currentSiteId);
        try {
            return vector.size();
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    public static Object vectorElementAt(java.util.Vector<?> vector, int index, long currentSiteId) {
        boolean ordered = beginCollectionReplay(vector, currentSiteId);
        try {
            return vector.elementAt(index);
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    public static void vectorRemoveAllElements(java.util.Vector<?> vector, long currentSiteId) {
        boolean ordered = beginCollectionReplay(vector, currentSiteId);
        try {
            vector.removeAllElements();
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    public static java.util.Set<?> mapKeySet(java.util.Map<?, ?> map, long currentSiteId) {
        boolean ordered = beginCollectionReplay(map, currentSiteId);
        try {
            java.util.Set<?> result = map.keySet();
            IdentityMapper.registerAllocation(result, currentSiteId);
            return result;
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    public static java.util.Set<?> mapEntrySet(java.util.Map<?, ?> map, long currentSiteId) {
        boolean ordered = beginCollectionReplay(map, currentSiteId);
        try {
            java.util.Set<?> result = map.entrySet();
            IdentityMapper.registerAllocation(result, currentSiteId);
            return result;
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object mapPut(java.util.Map map, Object key, Object value, long currentSiteId) {
        boolean ordered = beginCollectionReplay(map, currentSiteId);
        try {
            return map.put(key, value);
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object mapRemove(java.util.Map map, Object key, long currentSiteId) {
        boolean ordered = beginCollectionReplay(map, currentSiteId);
        try {
            return map.remove(key);
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    public static void mapClear(java.util.Map<?, ?> map, long currentSiteId) {
        boolean ordered = beginCollectionReplay(map, currentSiteId);
        try {
            map.clear();
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    public static java.util.Iterator<?> setIterator(java.util.Set<?> set, long currentSiteId) {
        boolean ordered = beginCollectionReplay(set, currentSiteId);
        try {
            java.util.Iterator<?> result = set.iterator();
            IdentityMapper.registerAllocation(result, currentSiteId);
            return result;
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    public static boolean iteratorHasNext(java.util.Iterator<?> iterator, long currentSiteId) {
        boolean ordered = beginCollectionReplay(iterator, currentSiteId);
        try {
            return iterator.hasNext();
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    public static Object iteratorNext(java.util.Iterator<?> iterator, long currentSiteId) {
        boolean ordered = beginCollectionReplay(iterator, currentSiteId);
        try {
            return iterator.next();
        } finally {
            if (ordered) {
                ReplayCoordinator.endAtomicReplay();
                isInside.set(false);
            }
        }
    }

    public static void replayObjectWait(Object lock, long waitSiteId, long wakeupSiteId) throws InterruptedException {
        if (isInside.get() || lock == null) return;
        isInside.set(true);
        try {
            awaitSyncTurn(BinarySchema.Event.THREAD_WAIT, lock, waitSiteId);
            awaitSyncTurn(BinarySchema.Event.THREAD_WAKEUP, null, wakeupSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void replayObjectWait(Object lock, long timeout, long waitSiteId, long wakeupSiteId) throws InterruptedException {
        replayObjectWait(lock, waitSiteId, wakeupSiteId);
    }

    public static void replayObjectWait(Object lock, long timeout, int nanos, long waitSiteId, long wakeupSiteId) throws InterruptedException {
        replayObjectWait(lock, waitSiteId, wakeupSiteId);
    }

    public static void replayObjectNotify(Object lock, long currentSiteId) {
        if (isInside.get() || lock == null) return;
        isInside.set(true);
        try {
            awaitSyncTurn(BinarySchema.Event.THREAD_NOTIFY, lock, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void replayObjectNotifyAll(Object lock, long currentSiteId) {
        if (isInside.get() || lock == null) return;
        isInside.set(true);
        try {
            awaitSyncTurn(BinarySchema.Event.THREAD_NOTIFY_ALL, lock, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void replayLock(Lock lock, long currentSiteId) {
        if (isInside.get() || lock == null) {
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(lock, null, currentSiteId);
            int packedType = BinarySchema.packType(BinarySchema.Event.MONITOR_ENTER, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void replayUnlock(Lock lock, long currentSiteId) {
        if (isInside.get() || lock == null) {
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(lock, null, currentSiteId);
            int packedType = BinarySchema.packType(BinarySchema.Event.MONITOR_EXIT, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static Condition replayNewCondition(Lock lock, long currentSiteId) {
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

    // ---- Condition await variants ----
    // Replay follows the captured trace order directly. ReentrantLock and
    // Condition operations are not executed physically because the trace already
    // encodes the synchronization order they established during capture.

    private static void conditionAwaitTurn(Condition condition, long currentSiteId) {
        long tid = Thread.currentThread().getId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return;
        BirthId conditionBirth = IdentityMapper.getBirthId(condition, null, currentSiteId);
        int packedType = BinarySchema.packType(BinarySchema.Event.THREAD_WAIT, BinarySchema.Flags.NONE);
        ReplayCoordinator.awaitTurn(roleId, packedType, conditionBirth, currentSiteId);
    }

    private static void conditionWakeupTurn(Condition condition, long wakeupSiteId) {
        long tid = Thread.currentThread().getId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, wakeupSiteId);
        if (roleId == -1) return;

        BirthId birthId = IdentityMapper.getBirthId(null, null, wakeupSiteId);
        int packedType = BinarySchema.packType(BinarySchema.Event.THREAD_WAKEUP, BinarySchema.Flags.NONE);
        ReplayCoordinator.awaitTurn(roleId, packedType, birthId, wakeupSiteId);
    }

    public static void replayAwait(Condition condition, long currentSiteId, long wakeupSiteId) throws InterruptedException {
        if (isInside.get() || condition == null) {
            return;
        }
        isInside.set(true);
        try {
            conditionAwaitTurn(condition, currentSiteId);
            conditionWakeupTurn(condition, wakeupSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void replayAwaitUninterruptibly(Condition condition, long currentSiteId, long wakeupSiteId) {
        if (isInside.get() || condition == null) {
            return;
        }
        isInside.set(true);
        try {
            conditionAwaitTurn(condition, currentSiteId);
            conditionWakeupTurn(condition, wakeupSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static long replayAwaitNanos(Condition condition, long nanosTimeout, long currentSiteId, long wakeupSiteId) throws InterruptedException {
        if (isInside.get() || condition == null) {
            return 1L;
        }
        isInside.set(true);
        try {
            conditionAwaitTurn(condition, currentSiteId);
            conditionWakeupTurn(condition, wakeupSiteId);
            return 1L;
        } finally {
            isInside.set(false);
        }
    }

    public static boolean replayAwaitUntil(Condition condition, Date deadline, long currentSiteId, long wakeupSiteId) throws InterruptedException {
        if (isInside.get() || condition == null) {
            return true;
        }
        isInside.set(true);
        try {
            conditionAwaitTurn(condition, currentSiteId);
            conditionWakeupTurn(condition, wakeupSiteId);
            return true;
        } finally {
            isInside.set(false);
        }
    }

    public static boolean replayAwaitTimed(Condition condition, long time, TimeUnit unit, long currentSiteId, long wakeupSiteId) throws InterruptedException {
        if (isInside.get() || condition == null) {
            return true;
        }
        isInside.set(true);
        try {
            conditionAwaitTurn(condition, currentSiteId);
            conditionWakeupTurn(condition, wakeupSiteId);
            return true;
        } finally {
            isInside.set(false);
        }
    }

    public static void replaySignal(Condition condition, long currentSiteId) {
        if (isInside.get() || condition == null) {
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId != -1) {
                BirthId birthId = IdentityMapper.getBirthId(condition, null, currentSiteId);
                int packedType = BinarySchema.packType(BinarySchema.Event.THREAD_NOTIFY, BinarySchema.Flags.NONE);
                ReplayCoordinator.awaitTurn(roleId, packedType, birthId, currentSiteId);
            }
        } finally {
            isInside.set(false);
        }
    }

    public static void replaySignalAll(Condition condition, long currentSiteId) {
        if (isInside.get() || condition == null) {
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId != -1) {
                BirthId birthId = IdentityMapper.getBirthId(condition, null, currentSiteId);
                int packedType = BinarySchema.packType(BinarySchema.Event.THREAD_NOTIFY_ALL, BinarySchema.Flags.NONE);
                ReplayCoordinator.awaitTurn(roleId, packedType, birthId, currentSiteId);
            }
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field access events (instance & static, volatile & non-volatile) ----

    public static void checkField(int eventType, Object owner, long currentSiteId,
                                boolean isVolatile, boolean isStatic,
                                String fieldName, String ownerName) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);

            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(eventType, flags);

            String evName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  %-5s%s %s.%s  fieldId=%d",
                    // seq, roleId, evName,
                    // isVolatile ? "(volatile)" : "",
                    // ownerName, fieldName, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array access events ----

    public static void checkArray(int eventType, Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            String evName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  %-12s %s[%d]  site=%d",
                    // seq, roleId, evName,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field read events (perform the actual load atomically inside the lock) ----
    // Without this, GETFIELD/GETSTATIC can race against a write from another thread
    // that was scheduled between the READ event and the physical instruction.

    public static int checkFieldReadInt(Object owner, long currentSiteId,
                                        boolean isVolatile, boolean isStatic,
                                        String fieldName, String ownerName) {
        if (isInside.get()) {
            try { return findField(ownerName, fieldName).getInt(owner); }
            catch (ReflectiveOperationException e) { return 0; }
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try { return findField(ownerName, fieldName).getInt(owner); }
                catch (ReflectiveOperationException e) { return 0; }
            }

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
                return readRawIntField(ownerName, fieldName, owner);
            }
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

            int[] result = new int[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId, () -> {
                try {
                    java.lang.reflect.Field f = findField(ownerName, fieldName);
                    Class<?> t = f.getType();
                    if      (t == int.class)     result[0] = f.getInt(owner);
                    else if (t == boolean.class) result[0] = f.getBoolean(owner) ? 1 : 0;
                    else if (t == byte.class)    result[0] = f.getByte(owner);
                    else if (t == short.class)   result[0] = f.getShort(owner);
                    else if (t == char.class)    result[0] = f.getChar(owner);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  READ%s %s.%s = %d  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static float checkFieldReadFloat(Object owner, long currentSiteId,
                                            boolean isVolatile, boolean isStatic,
                                            String fieldName, String ownerName) {
        if (isInside.get()) {
            try { return findField(ownerName, fieldName).getFloat(owner); }
            catch (ReflectiveOperationException e) { return 0f; }
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try { return findField(ownerName, fieldName).getFloat(owner); }
                catch (ReflectiveOperationException e) { return 0f; }
            }

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
                return readRawFloatField(ownerName, fieldName, owner);
            }
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

            float[] result = new float[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId, () -> {
                try { result[0] = findField(ownerName, fieldName).getFloat(owner); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  READ%s %s.%s = %f  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static long checkFieldReadLong(Object owner, long currentSiteId,
                                          boolean isVolatile, boolean isStatic,
                                          String fieldName, String ownerName) {
        if (isInside.get()) {
            try { return findField(ownerName, fieldName).getLong(owner); }
            catch (ReflectiveOperationException e) { return 0L; }
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try { return findField(ownerName, fieldName).getLong(owner); }
                catch (ReflectiveOperationException e) { return 0L; }
            }

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
                return readRawLongField(ownerName, fieldName, owner);
            }
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

            long[] result = new long[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId, () -> {
                try { result[0] = findField(ownerName, fieldName).getLong(owner); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  READ%s %s.%s = %dL  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static double checkFieldReadDouble(Object owner, long currentSiteId,
                                              boolean isVolatile, boolean isStatic,
                                              String fieldName, String ownerName) {
        if (isInside.get()) {
            try { return findField(ownerName, fieldName).getDouble(owner); }
            catch (ReflectiveOperationException e) { return 0.0; }
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try { return findField(ownerName, fieldName).getDouble(owner); }
                catch (ReflectiveOperationException e) { return 0.0; }
            }

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
                return readRawDoubleField(ownerName, fieldName, owner);
            }
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

            double[] result = new double[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId, () -> {
                try { result[0] = findField(ownerName, fieldName).getDouble(owner); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  READ%s %s.%s = %f  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkFieldReadObj(Object owner, long currentSiteId,
                                           boolean isVolatile, boolean isStatic,
                                           String fieldName, String ownerName) {
        if (isInside.get()) {
            try { return findField(ownerName, fieldName).get(owner); }
            catch (ReflectiveOperationException e) { return null; }
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try { return findField(ownerName, fieldName).get(owner); }
                catch (ReflectiveOperationException e) { return null; }
            }

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
                return readRawObjField(ownerName, fieldName, owner);
            }
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

            Object[] result = new Object[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId, () -> {
                try { result[0] = findField(ownerName, fieldName).get(owner); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  READ%s %s.%s = %s  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName,
                    // result[0] != null ? result[0].getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(result[0])) : "null",
                    // fieldId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array read events (perform the actual load atomically inside the lock) ----

    public static int checkArrayReadInt(Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) {
            if (array == null) return 0;
            Class<?> comp = array.getClass().getComponentType();
            if      (comp == int.class)     return ((int[])    array)[index];
            else if (comp == byte.class)    return ((byte[])   array)[index];
            else if (comp == boolean.class) return ((boolean[])array)[index] ? 1 : 0;
            else if (comp == short.class)   return ((short[])  array)[index];
            else if (comp == char.class)    return ((char[])   array)[index];
            return 0;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                Class<?> comp = array.getClass().getComponentType();
                if      (comp == int.class)     return ((int[])    array)[index];
                else if (comp == byte.class)    return ((byte[])   array)[index];
                else if (comp == boolean.class) return ((boolean[])array)[index] ? 1 : 0;
                else if (comp == short.class)   return ((short[])  array)[index];
                else if (comp == char.class)    return ((char[])   array)[index];
                return 0;
            }

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) {
                Class<?> comp = array.getClass().getComponentType();
                if      (comp == int.class)     return ((int[])    array)[index];
                else if (comp == byte.class)    return ((byte[])   array)[index];
                else if (comp == boolean.class) return ((boolean[])array)[index] ? 1 : 0;
                else if (comp == short.class)   return ((short[])  array)[index];
                else if (comp == char.class)    return ((char[])   array)[index];
                return 0;
            }
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            int[] result = new int[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index, () -> {
                Class<?> comp = array.getClass().getComponentType();
                if      (comp == int.class)     result[0] = ((int[])    array)[index];
                else if (comp == byte.class)    result[0] = ((byte[])   array)[index];
                else if (comp == boolean.class) result[0] = ((boolean[])array)[index] ? 1 : 0;
                else if (comp == short.class)   result[0] = ((short[])  array)[index];
                else if (comp == char.class)    result[0] = ((char[])   array)[index];
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ %s[%d] = %d  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, result[0], currentSiteId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static float checkArrayReadFloat(Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return array != null ? ((float[]) array)[index] : 0f;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return ((float[]) array)[index];

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) return ((float[]) array)[index];
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            float[] result = new float[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index,
                () -> result[0] = ((float[]) array)[index]);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ %s[%d] = %f  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, result[0], currentSiteId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static long checkArrayReadLong(Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return array != null ? ((long[]) array)[index] : 0L;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return ((long[]) array)[index];

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) return ((long[]) array)[index];
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            long[] result = new long[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index,
                () -> result[0] = ((long[]) array)[index]);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ %s[%d] = %dL  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, result[0], currentSiteId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static double checkArrayReadDouble(Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return array != null ? ((double[]) array)[index] : 0.0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return ((double[]) array)[index];

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) return ((double[]) array)[index];
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            double[] result = new double[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index,
                () -> result[0] = ((double[]) array)[index]);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ %s[%d] = %f  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, result[0], currentSiteId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkArrayReadObj(Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return array != null ? ((Object[]) array)[index] : null;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return ((Object[]) array)[index];

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) return ((Object[]) array)[index];
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            Object[] result = new Object[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index,
                () -> result[0] = ((Object[]) array)[index]);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ %s[%d] = %s  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index,
                    // result[0] != null ? result[0].getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(result[0])) : "null",
                    // currentSiteId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    /**
     * Coordinates the total-order turn for an AALOAD without returning the element.
     * The caller executes the real AALOAD bytecode afterward so the JVM preserves
     * the concrete element type on the operand stack (avoids VerifyError when the
     * element type is narrower than Object, e.g. Thread[]).
     */
    public static void checkArrayCoordinate(Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) return;
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index, () -> {});
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ(coord) %s[%d]  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field write events (perform the actual store atomically inside the lock) ----

    public static void checkFieldWriteInt(int value, Object owner, long currentSiteId,
                                          boolean isVolatile, boolean isStatic,
                                          String fieldName, String ownerName) {
        if (isInside.get()) {
            try {
                java.lang.reflect.Field f = findField(ownerName, fieldName);
                Class<?> t = f.getType();
                if      (t == int.class)     f.setInt(owner, value);
                else if (t == boolean.class) f.setBoolean(owner, value != 0);
                else if (t == byte.class)    f.setByte(owner, (byte) value);
                else if (t == short.class)   f.setShort(owner, (short) value);
                else if (t == char.class)    f.setChar(owner, (char) value);
            } catch (ReflectiveOperationException ignored) {}
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try {
                    java.lang.reflect.Field f = findField(ownerName, fieldName);
                    Class<?> t = f.getType();
                    if      (t == int.class)     f.setInt(owner, value);
                    else if (t == boolean.class) f.setBoolean(owner, value != 0);
                    else if (t == byte.class)    f.setByte(owner, (byte) value);
                    else if (t == short.class)   f.setShort(owner, (short) value);
                    else if (t == char.class)    f.setChar(owner, (char) value);
                } catch (ReflectiveOperationException ignored) {}
                return;
            }

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
                writeRawIntField(value, ownerName, fieldName, owner);
                return;
            }
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId, () -> {
                try {
                    java.lang.reflect.Field f = findField(ownerName, fieldName);
                    Class<?> t = f.getType();
                    if      (t == int.class)     f.setInt(owner, value);
                    else if (t == boolean.class) f.setBoolean(owner, value != 0);
                    else if (t == byte.class)    f.setByte(owner, (byte) value);
                    else if (t == short.class)   f.setShort(owner, (short) value);
                    else if (t == char.class)    f.setChar(owner, (char) value);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  WRITE%s %s.%s = %d  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkFieldWriteFloat(float value, Object owner, long currentSiteId,
                                            boolean isVolatile, boolean isStatic,
                                            String fieldName, String ownerName) {
        if (isInside.get()) {
            try { findField(ownerName, fieldName).setFloat(owner, value); }
            catch (ReflectiveOperationException ignored) {}
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try { findField(ownerName, fieldName).setFloat(owner, value); }
                catch (ReflectiveOperationException ignored) {}
                return;
            }

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
                writeRawFloatField(value, ownerName, fieldName, owner);
                return;
            }
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId, () -> {
                try {
                    findField(ownerName, fieldName).setFloat(owner, value);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  WRITE%s %s.%s = %f  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkFieldWriteLong(long value, Object owner, long currentSiteId,
                                           boolean isVolatile, boolean isStatic,
                                           String fieldName, String ownerName) {
        if (isInside.get()) {
            try { findField(ownerName, fieldName).setLong(owner, value); }
            catch (ReflectiveOperationException ignored) {}
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try { findField(ownerName, fieldName).setLong(owner, value); }
                catch (ReflectiveOperationException ignored) {}
                return;
            }

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
                writeRawLongField(value, ownerName, fieldName, owner);
                return;
            }
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId, () -> {
                try {
                    findField(ownerName, fieldName).setLong(owner, value);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  WRITE%s %s.%s = %dL  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkFieldWriteDouble(double value, Object owner, long currentSiteId,
                                             boolean isVolatile, boolean isStatic,
                                             String fieldName, String ownerName) {
        if (isInside.get()) {
            try { findField(ownerName, fieldName).setDouble(owner, value); }
            catch (ReflectiveOperationException ignored) {}
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try { findField(ownerName, fieldName).setDouble(owner, value); }
                catch (ReflectiveOperationException ignored) {}
                return;
            }

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
                writeRawDoubleField(value, ownerName, fieldName, owner);
                return;
            }
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId, () -> {
                try {
                    findField(ownerName, fieldName).setDouble(owner, value);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  WRITE%s %s.%s = %f  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkFieldWriteObj(Object value, Object owner, long currentSiteId,
                                          boolean isVolatile, boolean isStatic,
                                          String fieldName, String ownerName) {
        if (isInside.get()) {
            try { findField(ownerName, fieldName).set(owner, value); }
            catch (ReflectiveOperationException ignored) {}
            return;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                try { findField(ownerName, fieldName).set(owner, value); }
                catch (ReflectiveOperationException ignored) {}
                return;
            }

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
                writeRawObjField(value, ownerName, fieldName, owner);
                return;
            }
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, fieldId, () -> {
                try {
                    findField(ownerName, fieldName).set(owner, value);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  WRITE%s %s.%s = %s  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName,
                    // value != null ? value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value)) : "null",
                    // fieldId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array write events (perform the actual store atomically inside the lock) ----

    public static void checkArrayWriteInt(int value, Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                writeRawArrayInt(value, array, index);
                return;
            }

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) {
                writeRawArrayInt(value, array, index);
                return;
            }
            int packedType = (BinarySchema.Event.ARRAY_WRITE & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index, () -> {
                Class<?> comp = array.getClass().getComponentType();
                if      (comp == int.class)     ((int[])    array)[index] = value;
                else if (comp == byte.class)    ((byte[])   array)[index] = (byte)  value;
                else if (comp == boolean.class) ((boolean[])array)[index] = (value != 0);
                else if (comp == short.class)   ((short[])  array)[index] = (short) value;
                else if (comp == char.class)    ((char[])   array)[index] = (char)  value;
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_WRITE %s[%d] = %d  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, value, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkArrayWriteFloat(float value, Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                ((float[]) array)[index] = value;
                return;
            }

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) {
                ((float[]) array)[index] = value;
                return;
            }
            int packedType = (BinarySchema.Event.ARRAY_WRITE & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index,
                () -> ((float[]) array)[index] = value);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_WRITE %s[%d] = %f  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, value, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkArrayWriteLong(long value, Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                ((long[]) array)[index] = value;
                return;
            }

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) {
                ((long[]) array)[index] = value;
                return;
            }
            int packedType = (BinarySchema.Event.ARRAY_WRITE & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index,
                () -> ((long[]) array)[index] = value);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_WRITE %s[%d] = %dL  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, value, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkArrayWriteDouble(double value, Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                ((double[]) array)[index] = value;
                return;
            }

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) {
                ((double[]) array)[index] = value;
                return;
            }
            int packedType = (BinarySchema.Event.ARRAY_WRITE & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index,
                () -> ((double[]) array)[index] = value);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_WRITE %s[%d] = %f  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, value, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    public static void checkArrayWriteObj(Object value, Object array, int index, long currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                ((Object[]) array)[index] = value;
                return;
            }

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            if (IdentityMapper.shouldSkipObjectEvent(birthId, false)) {
                ((Object[]) array)[index] = value;
                return;
            }
            int packedType = (BinarySchema.Event.ARRAY_WRITE & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, index,
                () -> ((Object[]) array)[index] = value);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_WRITE %s[%d] = %s  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index,
                    // value != null ? value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value)) : "null",
                    // currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    // Reflection helper: finds a field by walking the class hierarchy.
    private static void writeRawArrayInt(int value, Object array, int index) {
        Class<?> comp = array.getClass().getComponentType();
        if      (comp == int.class)     ((int[])    array)[index] = value;
        else if (comp == byte.class)    ((byte[])   array)[index] = (byte) value;
        else if (comp == boolean.class) ((boolean[])array)[index] = (value != 0);
        else if (comp == short.class)   ((short[])  array)[index] = (short) value;
        else if (comp == char.class)    ((char[])   array)[index] = (char) value;
    }

    private static int readRawIntField(String ownerName, String fieldName, Object owner) {
        try {
            java.lang.reflect.Field f = findField(ownerName, fieldName);
            Class<?> t = f.getType();
            if      (t == int.class)     return f.getInt(owner);
            else if (t == boolean.class) return f.getBoolean(owner) ? 1 : 0;
            else if (t == byte.class)    return f.getByte(owner);
            else if (t == short.class)   return f.getShort(owner);
            else if (t == char.class)    return f.getChar(owner);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
        return 0;
    }

    private static float readRawFloatField(String ownerName, String fieldName, Object owner) {
        try { return findField(ownerName, fieldName).getFloat(owner); }
        catch (ReflectiveOperationException e) { return 0f; }
    }

    private static long readRawLongField(String ownerName, String fieldName, Object owner) {
        try { return findField(ownerName, fieldName).getLong(owner); }
        catch (ReflectiveOperationException e) { return 0L; }
    }

    private static double readRawDoubleField(String ownerName, String fieldName, Object owner) {
        try { return findField(ownerName, fieldName).getDouble(owner); }
        catch (ReflectiveOperationException e) { return 0.0; }
    }

    private static Object readRawObjField(String ownerName, String fieldName, Object owner) {
        try { return findField(ownerName, fieldName).get(owner); }
        catch (ReflectiveOperationException e) { return null; }
    }

    private static void writeRawIntField(int value, String ownerName, String fieldName, Object owner) {
        try {
            java.lang.reflect.Field f = findField(ownerName, fieldName);
            Class<?> t = f.getType();
            if      (t == int.class)     f.setInt(owner, value);
            else if (t == boolean.class) f.setBoolean(owner, value != 0);
            else if (t == byte.class)    f.setByte(owner, (byte) value);
            else if (t == short.class)   f.setShort(owner, (short) value);
            else if (t == char.class)    f.setChar(owner, (char) value);
        } catch (ReflectiveOperationException ignored) {}
    }

    private static void writeRawFloatField(float value, String ownerName, String fieldName, Object owner) {
        try { findField(ownerName, fieldName).setFloat(owner, value); }
        catch (ReflectiveOperationException ignored) {}
    }

    private static void writeRawLongField(long value, String ownerName, String fieldName, Object owner) {
        try { findField(ownerName, fieldName).setLong(owner, value); }
        catch (ReflectiveOperationException ignored) {}
    }

    private static void writeRawDoubleField(double value, String ownerName, String fieldName, Object owner) {
        try { findField(ownerName, fieldName).setDouble(owner, value); }
        catch (ReflectiveOperationException ignored) {}
    }

    private static void writeRawObjField(Object value, String ownerName, String fieldName, Object owner) {
        try { findField(ownerName, fieldName).set(owner, value); }
        catch (ReflectiveOperationException ignored) {}
    }

    private static java.lang.reflect.Field findField(String ownerName, String fieldName)
            throws ReflectiveOperationException {
        Class<?> cls = findOwnerClass(ownerName);
        while (cls != null) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(ownerName + "." + fieldName);
    }

    private static Class<?> findOwnerClass(String ownerName) throws ClassNotFoundException {
        String className = ownerName.replace('/', '.');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        try {
            return Class.forName(className, false, cl);
        } catch (ClassNotFoundException ignored) {
            try {
                return Class.forName(className, false, ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException ignoredAgain) {
                Class<?> stackClass = java.lang.StackWalker
                        .getInstance(java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE)
                        .walk(frames -> frames
                                .map(java.lang.StackWalker.StackFrame::getDeclaringClass)
                                .filter(cls -> cls.getName().equals(className))
                                .findFirst()
                                .orElse(null));
                if (stackClass != null) return stackClass;
                throw new ClassNotFoundException(className);
            }
        }
    }

    // ---- Atomic operations ----
    //
    // Non-CAS atomics (READ, WRITE, RMW): execute natively within controlLock.
    // Total order guarantees that by the time a thread's turn arrives all prior
    // atomic writes have already committed, so the native result is identical to
    // the captured one — no value injection needed.
    //
    // CAS (ATOMIC_CAS): the boolean/witness return drives control flow, and
    // object-identity differences between runs can cause a reference CAS to fail
    // spuriously.  We therefore inject the captured result and force-set the
    // post-op cell state, mirroring the existing RMW write-back mechanism.

    /**
     * Waits for this thread's turn in the total order and acquires controlLock,
     * keeping it held so the caller's native atomic call runs atomically with the
     * ordering step.  Must be paired with {@link #endAtomicReplay()}.
     */
    public static void beginAtomicReplay(Object receiver, int index, int eventType, long currentSiteId) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            boolean isArray = (index >= 0);
            int packedType;
            long objSite;
            int objCount;
            if (isArray) {
                packedType = (eventType & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) ;
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            } else {
                packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            ReplayCoordinator.beginAtomicReplay(roleId, packedType, objSite, objCount,
                    IdentityMapper.getCreatorRoleId(receiverBirth), isArray ? index : Long.MIN_VALUE);
            // controlLock is now held — endAtomicReplay() will advance + release
        } finally {
            isInside.set(false); // clear before returning so native call proceeds normally
        }
    }

    /**
     * Advances the total-order index and releases controlLock.
     * Must be called once after every successful beginAtomicReplay() call.
     */
    public static void endAtomicReplay() {
        ReplayCoordinator.endAtomicReplay();
    }

    // ---- CAS injection ----
    // CAS operations need result injection: the captured boolean/witness is returned
    // and the post-op cell state is force-set so subsequent reads see the correct value.

    public static int checkAtomicCasInt(Object receiver, int index, long currentSiteId) {
        if (isInside.get()) return 0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            final boolean isArray = (index >= 0);
            int packedType;
            long objSite;
            int objCount;
            if (isArray) {
                packedType = (BinarySchema.Event.ATOMIC_CAS & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) ;
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            } else {
                packedType = BinarySchema.packType(BinarySchema.Event.ATOMIC_CAS, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            // Force-set the post-op cell value within controlLock so no racing write
            // can interleave between the ordering step and the state update.
            java.util.function.IntConsumer writeBack = postOpValue -> {
                if (isArray)
                    ((java.util.concurrent.atomic.AtomicIntegerArray) receiver).set(index, postOpValue);
                else
                    ((java.util.concurrent.atomic.AtomicInteger) receiver).set(postOpValue);
            };
            int val = ReplayCoordinator.awaitTurnInt(roleId, packedType, objSite, objCount, writeBack,
                    IdentityMapper.getCreatorRoleId(receiverBirth), isArray ? index : Long.MIN_VALUE);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[CHECK-ATOMIC] seq=%d role=%d  ATOMIC_CAS     %s[%d] = %d  (inject)",
                    // seq, roleId,
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // index, val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static long checkAtomicCasLong(Object receiver, int index, long currentSiteId) {
        if (isInside.get()) return 0L;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            final boolean isArray = (index >= 0);
            int packedType;
            long objSite;
            int objCount;
            if (isArray) {
                packedType = (BinarySchema.Event.ATOMIC_CAS & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) ;
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            } else {
                packedType = BinarySchema.packType(BinarySchema.Event.ATOMIC_CAS, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            // Force-set the post-op cell value (data3/data4) within controlLock,
            // then return the captured return value (data1/data2).
            final boolean isArrayFinal = isArray;
            java.util.function.LongConsumer writeBack = postOpValue -> {
                if (isArrayFinal)
                    ((java.util.concurrent.atomic.AtomicLongArray) receiver).set(index, postOpValue);
                else
                    ((java.util.concurrent.atomic.AtomicLong) receiver).set(postOpValue);
            };
            long val = ReplayCoordinator.awaitTurnLong(roleId, packedType, objSite, objCount, writeBack,
                    IdentityMapper.getCreatorRoleId(receiverBirth), isArray ? index : Long.MIN_VALUE);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[CHECK-ATOMIC] seq=%d role=%d  ATOMIC_CAS     %s[%d] = %dL  (inject)",
                    // seq, roleId,
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // index, val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    public static Object checkAtomicCasObj(Object receiver, int index, long currentSiteId) {
        if (isInside.get()) return null;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            final boolean isArray = (index >= 0);
            int packedType;
            long objSite;
            int objCount;
            if (isArray) {
                packedType = (BinarySchema.Event.ATOMIC_CAS & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) ;
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            } else {
                packedType = BinarySchema.packType(BinarySchema.Event.ATOMIC_CAS, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            // Force-set post-op cell value within controlLock, return captured witness.
            java.util.function.Consumer<Object> writeBack = postOpValue -> {
                if (isArray)
                    ((java.util.concurrent.atomic.AtomicReferenceArray<Object>) receiver).set(index, postOpValue);
                else
                    ((java.util.concurrent.atomic.AtomicReference<Object>) receiver).set(postOpValue);
            };
            Object val = ReplayCoordinator.awaitTurnObj(roleId, packedType, objSite, objCount, writeBack,
                    IdentityMapper.getCreatorRoleId(receiverBirth), isArray ? index : Long.MIN_VALUE);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[CHECK-ATOMIC] seq=%d role=%d  ATOMIC_CAS     %s[%d] = %s  (inject)",
                    // seq, roleId,
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // index,
                    // val != null ? val.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static void checkException(Object exception, long siteId) {
        if (isInside.get()) return;
        if (exception == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(exception, null, siteId);
            int packedType = BinarySchema.packType(BinarySchema.Event.EXCEPTION_THROW, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId, siteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-THROW] seq=%d role=%d  %s  site=%d",
                    // seq, roleId,
                    // exception.getClass().getName(), siteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Nondeterministic value injection ----
    // During replay, instead of calling the real Random/System method, return the captured value.
    // The call site's siteId is used as the matching key (objSite=siteId, objCount=0).

    public static int replayNondetInt(long siteId) {
        if (isInside.get()) return 0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return 0;
            int packedType = BinarySchema.packType(BinarySchema.Event.NONDETERMINISTIC_INT, BinarySchema.Flags.NONE);
            int val = ReplayCoordinator.awaitTurnInt(roleId, packedType, siteId, 0);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format(
                    "[REPLAY-NONDET] epoch=%d seq=%d role=%d  nondet_int=%d  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, val, siteId));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static float replayNondetFloat(long siteId) {
        if (isInside.get()) return 0f;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return 0f;
            int packedType = BinarySchema.packType(BinarySchema.Event.NONDETERMINISTIC_INT, BinarySchema.Flags.NONE);
            int bits = ReplayCoordinator.awaitTurnInt(roleId, packedType, siteId, 0);
            float val = Float.intBitsToFloat(bits);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format(
                    "[REPLAY-NONDET] epoch=%d seq=%d role=%d  nondet_float=%f  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, val, siteId));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static long replayNondetLong(long siteId) {
        if (isInside.get()) return 0L;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return 0L;
            int packedType = BinarySchema.packType(BinarySchema.Event.NONDETERMINISTIC_LONG, BinarySchema.Flags.NONE);
            long val = ReplayCoordinator.awaitTurnLong(roleId, packedType, siteId, 0);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format(
                    "[REPLAY-NONDET] epoch=%d seq=%d role=%d  nondet_long=%d  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, val, siteId));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static double replayNondetDouble(long siteId) {
        if (isInside.get()) return 0.0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return 0.0;
            int packedType = BinarySchema.packType(BinarySchema.Event.NONDETERMINISTIC_LONG, BinarySchema.Flags.NONE);
            long bits = ReplayCoordinator.awaitTurnLong(roleId, packedType, siteId, 0);
            double val = Double.longBitsToDouble(bits);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format(
                    "[REPLAY-NONDET] epoch=%d seq=%d role=%d  nondet_double=%f  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, val, siteId));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static void preRegisterThread(Thread thread, long currentSiteId) {
        if (isInside.get() || thread == null) return;
        isInside.set(true);
        try {
            // Assign this thread's captured role before it runs a single instruction.
            // THREAD_START stores the child role in data1 for this exact Thread object.
            if (IdentityMapper.shouldSkipThread(thread)) return;
            long tid = thread.getId();
            BirthId birthId = IdentityMapper.getBirthId(thread, null, currentSiteId);
            int nextRole = ReplayCoordinator.childRoleForThreadStart(birthId.siteId, birthId.count, currentSiteId);
            if (nextRole == -1) {
                nextRole = ReplayCoordinator.peekNextPendingRole();
            }
            if (nextRole == -1) return;

            IdentityMapper.preAssignRole(tid, nextRole);
            ReplayCoordinator.checkIn(nextRole);
            // System.out.println("[PreRegister] role=" + nextRole);
        } finally {
            isInside.set(false);
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
            case BinarySchema.Event.COLLECTION_OP:        return "COLLECTION_OP";
            case BinarySchema.Event.CLASS_INIT_BEGIN:     return "CLASS_INIT_BEGIN";
            case BinarySchema.Event.CLASS_INIT_END:       return "CLASS_INIT_END";
            case BinarySchema.Event.EXCEPTION_THROW:        return "EXCEPTION_THROW";
            case BinarySchema.Event.NONDETERMINISTIC_INT:  return "NONDETERMINISTIC_INT";
            case BinarySchema.Event.NONDETERMINISTIC_LONG: return "NONDETERMINISTIC_LONG";
            default:                                       return "UNKNOWN(" + eventType + ")";
        }
    }
}
