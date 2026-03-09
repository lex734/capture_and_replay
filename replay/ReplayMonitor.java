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

    public static void checkSync(int eventType, Object lock, String siteString) {
        if (isInside.get()) return;
        // Allow null lock for events that have no associated object
        // (PARK without blocker, SLEEP, WAKEUP, YIELD, CLASS_INIT)
        if (lock == null && eventType != BinarySchema.Event.THREAD_PARK
            && eventType != BinarySchema.Event.THREAD_SLEEP
            && eventType != BinarySchema.Event.THREAD_WAKEUP
            && eventType != BinarySchema.Event.THREAD_YIELD
            && eventType != BinarySchema.Event.CLASS_INIT_BEGIN
            && eventType != BinarySchema.Event.CLASS_INIT_END) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(lock, null, currentSiteId);
            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            System.out.println("ReplayMonitor.checkSync: eventType=" + eventType + ", lock=" + lock + ", siteString=" + siteString + ", roleId=" + roleId + ", birthId.siteId=" + birthId.siteId + ", birthId.count=" + birthId.count + ", currentSiteId=" + currentSiteId);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field access events (instance & static, volatile & non-volatile) ----

    public static void checkField(int eventType, Object owner, String siteString,
                                boolean isVolatile, boolean isStatic,
                                String fieldName, String ownerName) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);

            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(eventType, flags);

            System.out.println("ReplayMonitor.checkField: eventType=" + eventType + ", owner=" + owner + ", siteString=" + siteString + ", isVolatile=" + isVolatile + ", isStatic=" + isStatic + ", fieldName=" + fieldName + ", ownerName=" + ownerName + ", roleId=" + roleId + ", birthId.siteId=" + birthId.siteId + ", birthId.count=" + birthId.count + ", fieldId=" + fieldId + ", flags=" + flags + ", packedType=" + packedType);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId);
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array access events ----

    public static void checkArray(int eventType, Object array, int index, String siteString) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            System.out.println("ReplayMonitor.checkArray: eventType=" + eventType + ", array=" + array + ", index=" + index + ", siteString=" + siteString + ", roleId=" + roleId + ", birthId.siteId=" + birthId.siteId + ", birthId.count=" + birthId.count + ", packedType=" + packedType);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index);
        } finally {
            isInside.set(false);
        }
    }

    // ---- Atomic operations ----
    // Scalar: stored (birthId.siteId, birthId.count, intValue) → awaitTurn arg1=birthId.siteId, arg2=intValue... 
    // BUT wait — for int, stored fields 3,4,5 are: birthId.siteId, birthId.count, intValue
    // awaitTurn only takes 2 value args, so it can only check intValue in arg2
    public static void checkAtomicInt(int writtenValue, Object receiver, int index, int eventType, String siteString) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            // Mirror of logAtomicInt: arg2 = intValue, arg1 = 0 (birthId.siteId not needed for verification)
            System.out.println("ReplayMonitor.checkAtomicInt(write): writtenValue=" + writtenValue + ", receiver=" + receiver + ", index=" + index + ", eventType=" + eventType + ", siteString=" + siteString + ", roleId=" + roleId + ", currentSiteId=" + currentSiteId);
            ReplayCoordinator.awaitTurn(roleId, eventType & 0xFF, 0, writtenValue, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void checkAtomicLong(long writtenValue, Object receiver, int index, int eventType, String siteString) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;
            // logAtomicLong only stored (int)longValue — high 32 bits were discarded at capture time
            System.out.println("ReplayMonitor.checkAtomicLong(write): writtenValue=" + writtenValue + ", receiver=" + receiver + ", index=" + index + ", eventType=" + eventType + ", siteString=" + siteString + ", roleId=" + roleId + ", currentSiteId=" + currentSiteId);
            ReplayCoordinator.awaitTurn(roleId, eventType & 0xFF, 0, (int)writtenValue, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void checkAtomicObj(Object writtenValue, Object receiver, int index, int eventType, String siteString) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            // logAtomicObj stored valueBirth.siteId as the value field
            BirthId valueBirth = IdentityMapper.getBirthId(writtenValue, null, currentSiteId);
            System.out.println("ReplayMonitor.checkAtomicObj(write): writtenValue=" + writtenValue + ", receiver=" + receiver + ", index=" + index + ", eventType=" + eventType + ", siteString=" + siteString + ", roleId=" + roleId + ", valueBirth.siteId=" + valueBirth.siteId + ", valueBirth.count=" + valueBirth.count + ", currentSiteId=" + currentSiteId);
            ReplayCoordinator.awaitTurn(roleId, eventType & 0xFF, 0, valueBirth.siteId, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static int checkAtomicInt(Object receiver, int index, int eventType, String siteString) {
        if (isInside.get()) return 0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            // Logged field 5 = intValue → comes back as awaitTurn's arg2
            System.out.println("ReplayMonitor.checkAtomicInt(read): receiver=" + receiver + ", index=" + index + ", eventType=" + eventType + ", siteString=" + siteString + ", roleId=" + roleId + ", currentSiteId=" + currentSiteId);
            return ReplayCoordinator.awaitTurnInt(roleId, eventType & 0xFF, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static long checkAtomicLong(Object receiver, int index, int eventType, String siteString) {
        if (isInside.get()) return 0L;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            // WARNING: capture only stored (int)longValue — high 32 bits are unrecoverable.
            // awaitTurnLong must reconstruct from the single stored int.
            System.out.println("ReplayMonitor.checkAtomicLong(read): receiver=" + receiver + ", index=" + index + ", eventType=" + eventType + ", siteString=" + siteString + ", roleId=" + roleId + ", currentSiteId=" + currentSiteId);
            return ReplayCoordinator.awaitTurnLong(roleId, eventType & 0xFF, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkAtomicObj(Object receiver, int index, int eventType, String siteString) {
        if (isInside.get()) return null;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            // Logged field 5 = valueBirth.siteId → coordinator resolves back to the live object
            System.out.println("ReplayMonitor.checkAtomicObj(read): receiver=" + receiver + ", index=" + index + ", eventType=" + eventType + ", siteString=" + siteString + ", roleId=" + roleId + ", currentSiteId=" + currentSiteId);
            return ReplayCoordinator.awaitTurnObj(roleId, eventType & 0xFF, currentSiteId);
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
            System.out.println("[PreRegister] tid=" + tid + " -> role=" + nextRole);
        } finally {
            isInside.set(false);
        }
    }
}
