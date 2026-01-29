package common;

import java.util.concurrent.atomic.AtomicLong;
import common.IdentityMapper.BirthId;

public class TraceLogger {
    private static final AtomicLong globalSeq = new AtomicLong(0);

    // for synchronization events such as MONITOR_ENTER, MONITOR_EXIT
    public static void logSync(int eventType, Object lock, int currentSiteId) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();

        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId birthId = IdentityMapper.getBirthId(lock, currentSiteId);

        BinarySchema.write(seq, (long)roleId, ((eventType & 0xFF) | (BinarySchema.Flags.NONE << 8)), birthId.siteId, birthId.count, currentSiteId);
    }

    // for field access events
    public static void logField(int eventType, Object owner, int currentSiteId, boolean isVolatile, boolean isStatic, int fieldId) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();

        int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) | (isStatic ? BinarySchema.Flags.IS_STATIC : 0);
        BirthId birthId = IdentityMapper.getBirthId(owner, currentSiteId);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

        BinarySchema.write(seq, (long)roleId, ((eventType & 0xFF) | (flags << 8)), birthId.siteId, birthId.count, fieldId);
    }

    // for array access events
    public static void logArray(int eventType, Object array, int index, int currentSiteId) {
        long seq = globalSeq.getAndIncrement();
        long tid = Thread.currentThread().getId();

        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId birthId = IdentityMapper.getBirthId(array, currentSiteId);

        BinarySchema.write(seq, roleId, ((eventType & 0xFF) | (BinarySchema.Flags.NONE << 8)), birthId.siteId, birthId.count, index);
    }
}
