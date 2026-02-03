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
        // If owner is null (Static), this returns BirthId.GLOBAL (0,0)
        BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
        
        // 4. Get the FieldId
        // If birthId is GLOBAL, this maps specifically to the static class+field name
        int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);

        // 5. Pack flags
        int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) | (isStatic ? BinarySchema.Flags.IS_STATIC : 0);

        // Logging for visibility
        if (isStatic) {
            String eventName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";
            System.out.println(String.format("[STATIC-RACE-COORD] seq=%d, thread=%d, roleId=%d, event=%s, field=%s.%s, globalId=%d", 
                seq, tid, roleId, eventName, ownerName, fieldName, fieldId));
        }

        // 6. Write to Binary Log
        // Every thread hitting the same static field will now write the SAME birthId.siteId (0), 
        // SAME birthId.count (0), and SAME fieldId. Only seq and roleId will differ.
        BinarySchema.write(seq, (long)roleId, ((eventType & 0xFF) | (flags << 8)), birthId.siteId, birthId.count, fieldId);
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

    // Helper method to get event name for sync events
    private static String getEventName(int eventType) {
        switch (eventType) {
            case BinarySchema.Event.MONITOR_ENTER: return "MONITOR_ENTER";
            case BinarySchema.Event.MONITOR_EXIT: return "MONITOR_EXIT";
            case BinarySchema.Event.THREAD_PARK: return "THREAD_PARK";
            case BinarySchema.Event.THREAD_UNPARK: return "THREAD_UNPARK";
            default: return "UNKNOWN(" + eventType + ")";
        }
    }
}
