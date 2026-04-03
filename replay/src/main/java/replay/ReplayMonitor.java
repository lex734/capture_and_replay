package replay;

import common.BinarySchema;
import common.IdentityMapper;
import common.IdentityMapper.BirthId;

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

            BirthId birthId = IdentityMapper.getBirthId(lock, null, currentSiteId);
            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, currentSiteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format(
                    "[CHECK-SYNC]  epoch=%d seq=%d role=%d  %-24s lock=%s  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    lock != null ? lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)) : "null",
                    currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field access events (instance & static, volatile & non-volatile) ----

    public static void checkField(int eventType, Object owner, int currentSiteId,
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

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format(
                    "[CHECK-FIELD] epoch=%d seq=%d role=%d  %-5s%s %s.%s  fieldId=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    isVolatile ? "(volatile)" : "",
                    ownerName, fieldName, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array access events ----

    public static void checkArray(int eventType, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            String evName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format(
                    "[CHECK-ARRAY] epoch=%d seq=%d role=%d  %-12s %s[%d]  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    index, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Atomic operations ----
    // Scalar: stored (birthId.siteId, birthId.count, intValue) → awaitTurn arg1=birthId.siteId, arg2=intValue... 
    // BUT wait — for int, stored fields 3,4,5 are: birthId.siteId, birthId.count, intValue
    // awaitTurn only takes 2 value args, so it can only check intValue in arg2
    public static void checkAtomicInt(int writtenValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            boolean isArray = (index >= 0);
            int packedType, objSite, objCount;
            if (isArray) {
                packedType = (eventType & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) | ((receiverBirth.siteId & 0xFFFF) << 16);
                objSite = receiverBirth.count;
                objCount = index;
            } else {
                packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            ReplayCoordinator.awaitTurn(roleId, packedType, objSite, objCount, writtenValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format("[CHECK-ATOMIC] epoch=%d seq=%d role=%d  %-12s %s = %d  (write)",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    writtenValue));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkAtomicLong(long writtenValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            boolean isArray = (index >= 0);
            int packedType, objSite, objCount;
            if (isArray) {
                packedType = (eventType & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) | ((receiverBirth.siteId & 0xFFFF) << 16);
                objSite = receiverBirth.count;
                objCount = index;
            } else {
                packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            ReplayCoordinator.awaitTurn(roleId, packedType, objSite, objCount, (int) writtenValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format("[CHECK-ATOMIC] epoch=%d seq=%d role=%d  %-12s %s = %dL  (write)",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    writtenValue));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkAtomicObj(Object writtenValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            boolean isArray = (index >= 0);
            int packedType, objSite, objCount;
            if (isArray) {
                packedType = (eventType & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) | ((receiverBirth.siteId & 0xFFFF) << 16);
                objSite = receiverBirth.count;
                objCount = index;
            } else {
                packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            BirthId valueBirth = IdentityMapper.getBirthId(writtenValue, null, currentSiteId);
            ReplayCoordinator.awaitTurn(roleId, packedType, objSite, objCount, valueBirth.siteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format("[CHECK-ATOMIC] epoch=%d seq=%d role=%d  %-12s %s = %s  (write)",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    writtenValue != null ? writtenValue.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(writtenValue)) : "null"));
        } finally {
            isInside.set(false);
        }
    }

    public static int checkAtomicInt(Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return 0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            boolean isArray = (index >= 0);
            int packedType, objSite, objCount;
            if (isArray) {
                packedType = (eventType & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) | ((receiverBirth.siteId & 0xFFFF) << 16);
                objSite = receiverBirth.count;
                objCount = index;
            } else {
                packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            int val = ReplayCoordinator.awaitTurnInt(roleId, packedType, objSite, objCount);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format("[CHECK-ATOMIC] epoch=%d seq=%d role=%d  %-12s %s = %d  (read→replay)",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static long checkAtomicLong(Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return 0L;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            boolean isArray = (index >= 0);
            int packedType, objSite, objCount;
            if (isArray) {
                packedType = (eventType & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) | ((receiverBirth.siteId & 0xFFFF) << 16);
                objSite = receiverBirth.count;
                objCount = index;
            } else {
                packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            long val = ReplayCoordinator.awaitTurnLong(roleId, packedType, objSite, objCount);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format("[CHECK-ATOMIC] epoch=%d seq=%d role=%d  %-12s %s = %dL  (read→replay)",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkAtomicObj(Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return null;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            boolean isArray = (index >= 0);
            int packedType, objSite, objCount;
            if (isArray) {
                packedType = (eventType & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) | ((receiverBirth.siteId & 0xFFFF) << 16);
                objSite = receiverBirth.count;
                objCount = index;
            } else {
                packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            Object val = ReplayCoordinator.awaitTurnObj(roleId, packedType, objSite, objCount);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format("[CHECK-ATOMIC] epoch=%d seq=%d role=%d  %-12s %s = %s  (read→replay)",
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

            BirthId birthId = IdentityMapper.getBirthId(exception, null, siteId);
            int packedType = BinarySchema.packType(BinarySchema.Event.EXCEPTION_THROW, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, siteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            System.out.println(String.format(
                    "[CHECK-THROW] epoch=%d seq=%d role=%d  %s  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId,
                    exception.getClass().getName(), siteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void preRegisterThread(Thread thread) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            // Assign this thread's roleId now, before it runs a single instruction.
            // Uses the next expected role from the trace at the current idx.
            long tid = thread.getId();
            int nextRole = ReplayCoordinator.peekNextPendingRole();
            if (nextRole == -1) return;

            IdentityMapper.preAssignRole(tid, nextRole);
            ReplayCoordinator.checkIn(nextRole);
            System.out.println("[PreRegister] role=" + nextRole);
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
            case BinarySchema.Event.CLASS_INIT_BEGIN:     return "CLASS_INIT_BEGIN";
            case BinarySchema.Event.CLASS_INIT_END:       return "CLASS_INIT_END";
            case BinarySchema.Event.EXCEPTION_THROW:      return "EXCEPTION_THROW";
            default:                                      return "UNKNOWN(" + eventType + ")";
        }
    }
}
