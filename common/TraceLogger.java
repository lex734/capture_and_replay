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
        BirthId birthId = IdentityMapper.getBirthId(lock, currentSiteId);

        String eventName = getEventName(eventType);
        System.out.println(String.format("[SYNC] seq=%d, thread=%d, roleId=%d, event=%s(%d), lock=%s, lockSiteId=%d, lockCount=%d, siteId=%d",
            seq, tid, roleId, eventName, eventType, lock != null ? lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)) : "null",
            birthId.siteId, birthId.count, currentSiteId));
        BinarySchema.write(seq, (long)roleId, ((eventType & 0xFF) | (BinarySchema.Flags.NONE << 8)), birthId.siteId, birthId.count, currentSiteId);
    }

    // for field access events
    public static void logField(int eventType, Object owner, String siteString, boolean isVolatile, boolean isStatic, String fieldName) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
    
        int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) | (isStatic ? BinarySchema.Flags.IS_STATIC : 0);
        BirthId birthId = IdentityMapper.getBirthId(owner, currentSiteId);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        int fieldId = IdentityMapper.getFieldId(birthId, fieldName, currentSiteId);

        String eventName = (eventType == BinarySchema.Event.FIELD_READ) ? "FIELD_READ" : "FIELD_WRITE";
        System.out.println(String.format("[FIELD] seq=%d, thread=%d, roleId=%d, event=%s(%d), owner=%s, ownerSiteId=%d, ownerCount=%d, fieldId=%d, volatile=%b, static=%b, siteId=%d",
            seq, tid, roleId, eventName, eventType,
            owner != null ? owner.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(owner)) : "null",
            birthId.siteId, birthId.count, fieldId, isVolatile, isStatic, currentSiteId));

        BinarySchema.write(seq, (long)roleId, ((eventType & 0xFF) | (flags << 8)), birthId.siteId, birthId.count, fieldId);
    }

    // for array access events
    public static void logArray(int eventType, Object array, int index, String siteString) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId birthId = IdentityMapper.getBirthId(array, currentSiteId);
        
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
