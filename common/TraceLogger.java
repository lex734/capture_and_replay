package common;

import java.util.concurrent.atomic.AtomicLong;
import common.IdentityMapper.BirthId;

public class TraceLogger {
    private static final AtomicLong globalSeq = new AtomicLong(0);

    // for synchronization events such as MONITOR_ENTER, MONITOR_EXIT
    public static void logSync(int eventType, Object lock, String siteString) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId birthId = IdentityMapper.getBirthId(lock, null,currentSiteId);
        String eventName = getEventName(eventType);
        System.out.println(String.format("[SYNC] seq=%d, thread=%d, roleId=%d, event=%s(%d), lock=%s, lockSiteId=%d, lockCount=%d, siteId=%d",
            seq, tid, roleId, eventName, eventType, lock != null ? lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)) : "null",
            birthId.siteId, birthId.count, currentSiteId));
        BinarySchema.write(seq, (long)roleId, ((eventType & 0xFF) | (BinarySchema.Flags.NONE << 8)), birthId.siteId, birthId.count, currentSiteId);
    }

    public static void logField(int eventType, Object owner, String siteString, boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();
        
        // 1. Get the instruction site (where the thread is right now)
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        
        // 2. Get the role (Who is doing this)
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
    
        // 3. Get the BirthId
        // For statics (owner == null), IdentityMapper returns BirthId.GLOBAL (0, 0)
        BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
        
        // 4. Get the FieldId
        // Consistent ID for the same field across all threads
        int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
    
        // 5. Pack flags and event type
        int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) | 
                    (isStatic ? BinarySchema.Flags.IS_STATIC : 0);
        
        int packedType = BinarySchema.packType(eventType, flags);
    
        // Logging for visibility (Useful for debugging the deterministic interleaving)
        String eventName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";
        System.out.println(String.format("[STATIC-COORD] seq=%d, role=%d, event=%s, field=%s.%s, fieldId=%d", 
            seq, roleId, eventName, ownerName, fieldName, fieldId));
    
        // 6. Write to Binary Log
        // Data field contains the fieldId (the unique coordinate for this variable)
        BinarySchema.write(seq, (long)roleId, packedType, birthId.siteId, birthId.count, fieldId);
    }

    // for array access events
    public static void logArray(int eventType, Object array, int index, String siteString) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);

        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
        
        String eventName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";
        System.out.println(String.format("[ARRAY] seq=%d, thread=%d, roleId=%d, event=%s(%d), array=%s, arraySiteId=%d, arrayCount=%d, index=%d, siteId=%d",
            seq, tid, roleId, eventName, eventType,
            array != null ? array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)) : "null",
            birthId.siteId, birthId.count, index, currentSiteId));

        BinarySchema.write(seq, roleId, ((eventType & 0xFF) | (BinarySchema.Flags.NONE << 8)), birthId.siteId, birthId.count, index);
    }

    // For atomic operations — int/boolean return values
    public static void logAtomicInt(int returnValue, int eventType, String siteString) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        String eventName = getEventName(eventType);
        System.out.println(String.format("[ATOMIC] seq=%d, thread=%d, roleId=%d, event=%s(%d), returnInt=%d, siteId=%d",
            seq, tid, roleId, eventName, eventType, returnValue, currentSiteId));
        BinarySchema.write(seq, (long)roleId, (eventType & 0xFF), 0, returnValue, currentSiteId);
    }

    // For atomic operations — long return values
    public static void logAtomicLong(long returnValue, int eventType, String siteString) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        String eventName = getEventName(eventType);
        System.out.println(String.format("[ATOMIC] seq=%d, thread=%d, roleId=%d, event=%s(%d), returnLong=%d, siteId=%d",
            seq, tid, roleId, eventName, eventType, returnValue, currentSiteId));
        BinarySchema.write(seq, (long)roleId, (eventType & 0xFF), (int)(returnValue >> 32), (int)returnValue, currentSiteId);
    }

    // For atomic operations — Object return values (uses BirthId for identity)
    public static void logAtomicObj(Object returnValue, int eventType, String siteString) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId birthId = IdentityMapper.getBirthId(returnValue, null, currentSiteId);
        String eventName = getEventName(eventType);
        System.out.println(String.format("[ATOMIC] seq=%d, thread=%d, roleId=%d, event=%s(%d), returnObj=%s, birthSiteId=%d, birthCount=%d, siteId=%d",
            seq, tid, roleId, eventName, eventType,
            returnValue != null ? returnValue.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(returnValue)) : "null",
            birthId.siteId, birthId.count, currentSiteId));
        BinarySchema.write(seq, (long)roleId, (eventType & 0xFF), birthId.siteId, birthId.count, currentSiteId);
    }

    // For atomic operations — void methods (set, lazySet)
    public static void logAtomicVoid(int eventType, String siteString) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        String eventName = getEventName(eventType);
        System.out.println(String.format("[ATOMIC] seq=%d, thread=%d, roleId=%d, event=%s(%d), returnVoid, siteId=%d",
            seq, tid, roleId, eventName, eventType, currentSiteId));
        BinarySchema.write(seq, (long)roleId, (eventType & 0xFF), 0, 0, currentSiteId);
    }

    // Helper method to get event name for sync events
    private static String getEventName(int eventType) {
        switch (eventType) {
            case BinarySchema.Event.MONITOR_ENTER: return "MONITOR_ENTER";
            case BinarySchema.Event.MONITOR_EXIT: return "MONITOR_EXIT";
            case BinarySchema.Event.THREAD_PARK: return "THREAD_PARK";
            case BinarySchema.Event.THREAD_UNPARK: return "THREAD_UNPARK";
            case BinarySchema.Event.THREAD_START: return "THREAD_START";
            case BinarySchema.Event.THREAD_JOIN: return "THREAD_JOIN";
            case BinarySchema.Event.THREAD_INTERRUPT: return "THREAD_INTERRUPT";
            case BinarySchema.Event.THREAD_SLEEP: return "THREAD_SLEEP";
            case BinarySchema.Event.THREAD_WAKEUP: return "THREAD_WAKEUP";
            case BinarySchema.Event.THREAD_YIELD: return "THREAD_YIELD";
            case BinarySchema.Event.THREAD_WAIT: return "THREAD_WAIT";
            case BinarySchema.Event.THREAD_NOTIFY: return "THREAD_NOTIFY";
            case BinarySchema.Event.THREAD_NOTIFY_ALL: return "THREAD_NOTIFY_ALL";
            case BinarySchema.Event.THREAD_JOIN_TIMEOUT: return "THREAD_JOIN_TIMEOUT";
            case BinarySchema.Event.THREAD_INTERRUPT_CHECK: return "THREAD_INTERRUPT_CHECK";
            case BinarySchema.Event.ATOMIC_READ: return "ATOMIC_READ";
            case BinarySchema.Event.ATOMIC_WRITE: return "ATOMIC_WRITE";
            case BinarySchema.Event.ATOMIC_RMW: return "ATOMIC_RMW";
            case BinarySchema.Event.CLASS_INIT_BEGIN: return "CLASS_INIT_BEGIN";
            case BinarySchema.Event.CLASS_INIT_END: return "CLASS_INIT_END";
            default: return "UNKNOWN(" + eventType + ")";
        }
    }
}
