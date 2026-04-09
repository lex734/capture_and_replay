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
            // System.out.println(String.format(
                    // "[CHECK-SYNC]  epoch=%d seq=%d role=%d  %-24s lock=%s  site=%d",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    // lock != null ? lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)) : "null",
                    // currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field access events ----
    // Each method blocks until the event's turn in the total order, then returns
    // the value that was recorded during capture.  The SyncTransformer uses the
    // returned value as the actual field value (for reads) or the value to write
    // (for writes), eliminating ordering dependence between reads and writes.

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
            int val = ReplayCoordinator.awaitTurnFieldInt(roleId, packed[0], site[0], count[0], naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";
            // System.out.println(String.format("[CHECK-FIELD] epoch=%d seq=%d role=%d  %-5s%s %s.%s = %d",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    // isVolatile ? "(volatile)" : "", ownerName, fieldName, val));
            return val;
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
            long val = ReplayCoordinator.awaitTurnFieldLong(roleId, packed[0], site[0], count[0], naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";
            // System.out.println(String.format("[CHECK-FIELD] epoch=%d seq=%d role=%d  %-5s%s %s.%s = %dL",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    // isVolatile ? "(volatile)" : "", ownerName, fieldName, val));
            return val;
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
            Object val = ReplayCoordinator.awaitTurnFieldObj(roleId, packed[0], site[0], count[0], naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";
            // System.out.println(String.format("[CHECK-FIELD] epoch=%d seq=%d role=%d  %-5s%s %s.%s = %s",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    // isVolatile ? "(volatile)" : "", ownerName, fieldName,
                    // val != null ? val.getClass().getSimpleName()
                    //         + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
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
            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (eventType & 0xFF)
                    | (BinarySchema.Flags.IS_ARRAY_VALUED << 8)
                    | ((birthId.siteId & 0xFFFF) << 16);
            int val = ReplayCoordinator.awaitTurnArrayInt(roleId, packedType, birthId.count, index, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";
            // System.out.println(String.format("[CHECK-ARRAY] epoch=%d seq=%d role=%d  %-12s %s[%d] = %d",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, val));
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
            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (eventType & 0xFF)
                    | (BinarySchema.Flags.IS_ARRAY_VALUED << 8)
                    | ((birthId.siteId & 0xFFFF) << 16);
            long val = ReplayCoordinator.awaitTurnArrayLong(roleId, packedType, birthId.count, index, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";
            // System.out.println(String.format("[CHECK-ARRAY] epoch=%d seq=%d role=%d  %-12s %s[%d] = %dL",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, val));
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
            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (eventType & 0xFF)
                    | (BinarySchema.Flags.IS_ARRAY_VALUED << 8)
                    | ((birthId.siteId & 0xFFFF) << 16);
            Object val = ReplayCoordinator.awaitTurnArrayObj(roleId, packedType, birthId.count, index, naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            String evName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";
            // System.out.println(String.format("[CHECK-ARRAY] epoch=%d seq=%d role=%d  %-12s %s[%d] = %s",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, evName,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index,
                    // val != null ? val.getClass().getSimpleName()
                    //         + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
            return val;
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

    // ---- CAS injection ----
    // CAS outcome is non-deterministic across schedules; inject the captured result
    // and advance the epoch without reporting divergence.

    public static int injectAtomicCasInt(Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return 0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return 0;
            BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            int val = ReplayCoordinator.awaitTurnCasInt(roleId,
                    buildAtomicPackedType(eventType, b, index), atomicObjSite(b, index), atomicObjCount(b, index));
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[INJECT-CAS]  epoch=%d seq=%d role=%d  %-12s %s → %d",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // val));
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
            BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            long val = ReplayCoordinator.awaitTurnCasLong(roleId,
                    buildAtomicPackedType(eventType, b, index), atomicObjSite(b, index), atomicObjCount(b, index));
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[INJECT-CAS]  epoch=%d seq=%d role=%d  %-12s %s → %dL",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // val));
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
            BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            Object val = ReplayCoordinator.awaitTurnCasObj(roleId,
                    buildAtomicPackedType(eventType, b, index), atomicObjSite(b, index), atomicObjCount(b, index));
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[INJECT-CAS]  epoch=%d seq=%d role=%d  %-12s %s → %s",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // val != null ? val.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    // ---- RMW divergence check ----
    // The atomic operation has already executed; natural value is always returned.
    // Divergence is logged but not injected (can't undo the completed operation).

    public static int checkAtomicRmwInt(int naturalValue, Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return naturalValue;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return naturalValue;
            BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            int val = ReplayCoordinator.awaitTurnRmwInt(roleId,
                    buildAtomicPackedType(eventType, b, index), atomicObjSite(b, index), atomicObjCount(b, index),
                    naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[CHECK-RMW]   epoch=%d seq=%d role=%d  %-12s %s = %d",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // val));
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
            BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            long val = ReplayCoordinator.awaitTurnRmwLong(roleId,
                    buildAtomicPackedType(eventType, b, index), atomicObjSite(b, index), atomicObjCount(b, index),
                    naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[CHECK-RMW]   epoch=%d seq=%d role=%d  %-12s %s = %dL",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // val));
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
            BirthId b = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            Object val = ReplayCoordinator.awaitTurnRmwObj(roleId,
                    buildAtomicPackedType(eventType, b, index), atomicObjSite(b, index), atomicObjCount(b, index),
                    naturalValue);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[CHECK-RMW]   epoch=%d seq=%d role=%d  %-12s %s = %s",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId, getEventName(eventType),
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // val != null ? val.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
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
            // System.out.println(String.format(
                    // "[CHECK-THROW] epoch=%d seq=%d role=%d  %s  site=%d",
                    // seq >>> 32, seq & 0xFFFFFFFFL, roleId,
                    // exception.getClass().getName(), siteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Nondeterministic value injection ----
    // During replay, instead of calling the real Random/System method, return the captured value.
    // The call site's siteId is used as the matching key (objSite=0, objCount=siteId).

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
            System.out.println(String.format(
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
            System.out.println(String.format(
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
            System.out.println(String.format(
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
            System.out.println(String.format(
                    "[REPLAY-NONDET] epoch=%d seq=%d role=%d  nondet_double=%f  site=%d",
                    seq >>> 32, seq & 0xFFFFFFFFL, roleId, val, siteId));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static void preRegisterThread(Thread thread) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
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
            // System.out.println("[PreRegister] role=" + nextRole + " (parent role=" + parentRole + ")");
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
            case BinarySchema.Event.CLASS_INIT_BEGIN:     return "CLASS_INIT_BEGIN";
            case BinarySchema.Event.CLASS_INIT_END:       return "CLASS_INIT_END";
            case BinarySchema.Event.EXCEPTION_THROW:        return "EXCEPTION_THROW";
            case BinarySchema.Event.NONDETERMINISTIC_INT:  return "NONDETERMINISTIC_INT";
            case BinarySchema.Event.NONDETERMINISTIC_LONG: return "NONDETERMINISTIC_LONG";
            default:                                       return "UNKNOWN(" + eventType + ")";
        }
    }
}
