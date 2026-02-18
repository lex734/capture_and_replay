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

    public static void logSync(int eventType, Object lock, String siteString) {
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
            BirthId birthId = IdentityMapper.getBirthId(lock, null, currentSiteId);
            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field access events (instance & static, volatile & non-volatile) ----

    public static void logField(int eventType, Object owner, String siteString,
                                boolean isVolatile, boolean isStatic,
                                String fieldName, String ownerName) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);

            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(eventType, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId);
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array access events ----

    public static void logArray(int eventType, Object array, int index, String siteString) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index);
        } finally {
            isInside.set(false);
        }
    }

    // ---- Atomic operations ----

    public static void logAtomicInt(int returnValue, int eventType, String siteString) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            ReplayCoordinator.awaitTurn(roleId, eventType & 0xFF, 0, returnValue, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void logAtomicLong(long returnValue, int eventType, String siteString) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            ReplayCoordinator.awaitTurn(roleId, eventType & 0xFF, (int)(returnValue >> 32), (int)returnValue, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void logAtomicObj(Object returnValue, int eventType, String siteString) {
        if (isInside.get()) return;
        
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            BirthId birthId = IdentityMapper.getBirthId(returnValue, null, currentSiteId);

            ReplayCoordinator.awaitTurn(roleId, eventType & 0xFF, birthId.siteId, birthId.count, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }

    public static void logAtomicVoid(int eventType, String siteString) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int currentSiteId = IdentityMapper.getSiteId(siteString);
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            ReplayCoordinator.awaitTurn(roleId, eventType & 0xFF, 0, 0, currentSiteId);
        } finally {
            isInside.set(false);
        }
    }
}
