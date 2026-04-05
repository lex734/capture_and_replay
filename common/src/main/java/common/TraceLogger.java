package common;

import java.util.concurrent.atomic.AtomicLong;
import common.IdentityMapper.BirthId;

public class TraceLogger {
    // ---- Total-order sequence tracking ----
    //
    // Every event gets a globally unique, monotonically increasing sequence
    // number that reflects the actual execution order across all threads.
    // This guarantees that intermediate states are fully deterministic during
    // replay, not just final outcomes.
    private static final AtomicLong globalSeq = new AtomicLong(0);

    public static long nextSeq() {
        return globalSeq.incrementAndGet();
    }

    // for synchronization events such as MONITOR_ENTER, MONITOR_EXIT
    public static void logSync(int eventType, Object lock, int currentSiteId) {
        long tid = Thread.currentThread().threadId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return;
        BirthId birthId = IdentityMapper.getBirthId(lock, null, currentSiteId);
        String eventName = getEventName(eventType);

        // For THREAD_START, eagerly pre-assign the child thread's roleId and store it
        // in data1. This lets the replay coordinator enforce causality: the child's
        // events must be ordered AFTER the parent's THREAD_START, not before.
        if (eventType == BinarySchema.Event.THREAD_START && lock instanceof Thread) {
            long childTid = ((Thread) lock).threadId();
            int childRoleId = IdentityMapper.getRoleIdBySite(childTid, currentSiteId);
            long seq = nextSeq();
            // System.out.println(String.format(
                    // "[SYNC]   seq=%d role=%d  %-24s lock=%s  site=%d  childRole=%d",
                    // seq, roleId, eventName,
                    // lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)),
                    // currentSiteId, childRoleId));
            BinarySchema.write(seq, (long) roleId, ((eventType & 0xFF) | (BinarySchema.Flags.NONE << 8)),
                    birthId.siteId, birthId.count, childRoleId, currentSiteId);
            return;
        }

        long seq = nextSeq();
        // System.out.println(String.format(
                // "[SYNC]   seq=%d role=%d  %-24s lock=%s  site=%d",
                // seq, roleId, eventName,
                // lock != null
                //         ? lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock))
                //         : "null",
                // currentSiteId));
        BinarySchema.write(seq, (long) roleId, ((eventType & 0xFF) | (BinarySchema.Flags.NONE << 8)), birthId.siteId,
                birthId.count, currentSiteId);
    }

    public static void logField(int eventType, Object owner, int currentSiteId, boolean isVolatile, boolean isStatic,
            String fieldName, String ownerName) {
        long tid = Thread.currentThread().threadId();

        // 2. Get the role (Who is doing this)
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return;

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

        // 6. Assign seq as late as possible — after all computation, just before
        //    writing to the trace — to minimise the gap between the sequence number
        //    and the actual field access that follows logField returning.
        long seq = nextSeq();
        String eventName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";
        // System.out.println(String.format(
                // "[FIELD]  seq=%d role=%d  %-5s%s %s.%s  fieldId=%d",
                // seq, roleId, eventName,
                // isVolatile ? "(volatile)" : "",
                // ownerName, fieldName, fieldId));

        // 7. Write to Binary Log
        // Data field contains the fieldId (the unique coordinate for this variable)
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
    }

    // for array access events
    public static void logArray(int eventType, Object array, int index, int currentSiteId) {
        long tid = Thread.currentThread().threadId();

        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return;

        BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);

        long seq = nextSeq();
        String eventName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";
        // System.out.println(String.format(
                // "[ARRAY]  seq=%d role=%d  %-12s %s[%d]  site=%d",
                // seq, roleId, eventName,
                // array != null
                //         ? array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array))
                //         : "null",
                // index, currentSiteId));

        BinarySchema.write(seq, roleId, ((eventType & 0xFF) | (BinarySchema.Flags.NONE << 8)), birthId.siteId,
                birthId.count, index);
    }

    // ---- Unified atomic loggers ----
    // All atomic operations capture WHERE (receiver + index) and WHAT (value).
    //
    // Binary record layout — ARRAY atomics (IS_ARRAY_ATOMIC flag set):
    // packedType upper 16 bits = receiver BirthId.siteId (which allocation site)
    // objSite = receiver BirthId.count (which instance)
    // objCount = array index (WHERE within the array)
    // data = value (WHAT was written/read)
    //
    // Binary record layout — SCALAR atomics (no flag):
    // objSite = receiver BirthId.siteId
    // objCount = receiver BirthId.count
    // data = value (int, lo-32-of-long, or value BirthId.siteId for Object)

    private static int packAtomicArrayType(int eventType, int receiverSiteId) {
        return (eventType & 0xFF)
                | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8)
                | ((receiverSiteId & 0xFFFF) << 16);
    }

    /**
     * Atomic logger for int-sized values (int, boolean, byte, short, char).
     *
     * @param returnValue  the return value of the operation (or written value for void set)
     * @param postOpValue  the value stored in the atomic cell after the operation;
     *                     equals returnValue for READ/WRITE, may differ for RMW
     *                     (e.g. getAndIncrement returns old, postOp = old+1)
     * @param receiver     the atomic object instance
     * @param index        array element index, or -1 for scalar atomics
     */
    public static void logAtomicInt(int returnValue, int postOpValue, Object receiver, int index, int eventType, int currentSiteId) {
        long tid = Thread.currentThread().threadId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return;

        BirthId birthId = IdentityMapper.getBirthId(receiver, null, currentSiteId);
        boolean isArray = (index >= 0);

        long seq = nextSeq();
        String eventName = getEventName(eventType);
        String receiverStr = receiver != null
                ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver))
                : "null";
        // if (isArray) {
        //     // System.out.println(String.format(
        //             "[ATOMIC] seq=%d role=%d  %-12s %s[%d] = %d",
        //             seq, roleId, eventName, receiverStr, index, returnValue));
        // } else {
        //     // System.out.println(String.format(
        //             "[ATOMIC] seq=%d role=%d  %-12s %s = %d",
        //             seq, roleId, eventName, receiverStr, returnValue));
        // }

        // data1 = postOpValue (write-back value for RMW replay), data2 = returnValue
        if (isArray) {
            int packedType = packAtomicArrayType(eventType, birthId.siteId);
            BinarySchema.write(seq, (long) roleId, packedType, birthId.count, index, postOpValue, returnValue);
        } else {
            int packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
            BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, postOpValue, returnValue);
        }
    }

    /**
     * Atomic logger for long values.
     * Array atomics: stores index + low 32 bits of long value.
     * Scalar atomics: stores full receiver BirthId + low 32 bits of long value.
     */
    public static void logAtomicLong(long longValue, long postOpValue, Object receiver, int index, int eventType, int currentSiteId) {
        long tid = Thread.currentThread().threadId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return;

        BirthId birthId = IdentityMapper.getBirthId(receiver, null, currentSiteId);
        boolean isArray = (index >= 0);

        long seq = nextSeq();
        String eventName = getEventName(eventType);
        String receiverStr = receiver != null
                ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver))
                : "null";
        // if (isArray) {
        //     // System.out.println(String.format(
        //             "[ATOMIC] seq=%d role=%d  %-12s %s[%d] = %dL",
        //             seq, roleId, eventName, receiverStr, index, longValue));
        // } else {
        //     // System.out.println(String.format(
        //             "[ATOMIC] seq=%d role=%d  %-12s %s = %dL",
        //             seq, roleId, eventName, receiverStr, longValue));
        // }

        if (isArray) {
            int packedType = packAtomicArrayType(eventType, birthId.siteId);
            BinarySchema.write(seq, (long) roleId, packedType, birthId.count, index, (int) (longValue >> 32),
                    (int) longValue, (int) (postOpValue >> 32), (int) postOpValue);
        } else {
            int packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
            BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, (int) (longValue >> 32),
                    (int) longValue, (int) (postOpValue >> 32), (int) postOpValue);
        }
    }

    /**
     * Atomic logger for Object values (AtomicReference, AtomicReferenceArray).
     * Array atomics: stores index + value's BirthId.siteId.
     * Scalar atomics: stores receiver BirthId + value's BirthId.siteId.
     */
    public static void logAtomicObj(Object objValue, Object postOpValue, Object receiver, int index, int eventType, int currentSiteId) {
        long tid = Thread.currentThread().threadId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        if (roleId == -1) return;

        BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
        BirthId valueBirth = IdentityMapper.getBirthId(objValue, null, currentSiteId);
        BirthId postOpValueBirth = IdentityMapper.getBirthId(postOpValue, null, currentSiteId);
        boolean isArray = (index >= 0);

        long seq = nextSeq();
        String eventName = getEventName(eventType);
        String receiverStr = receiver != null
                ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver))
                : "null";
        String valueStr = objValue != null
                ? objValue.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(objValue))
                : "null";
        // if (isArray) {
        //     // System.out.println(String.format(
        //             "[ATOMIC] seq=%d role=%d  %-12s %s[%d] = %s",
        //             seq, roleId, eventName, receiverStr, index, valueStr));
        // } else {
        //     // System.out.println(String.format(
        //             "[ATOMIC] seq=%d role=%d  %-12s %s = %s",
        //             seq, roleId, eventName, receiverStr, valueStr));
        // }

        // Pack creator role IDs for both value objects into one int so that
        // awaitTurnObj can call resolveByBirthId with the correct creatorRoleId
        // when per-role-per-site birth counts are in use.
        // bits 31-16: creator role of the return-value object (data1/data2)
        // bits 15-0:  creator role of the post-op object     (data3/data4)
        int valueCreatorRole   = (valueBirth    instanceof IdentityMapper.BirthId.Heap)
                                 ? ((IdentityMapper.BirthId.Heap) valueBirth).creatorRoleId    : -1;
        int postOpCreatorRole  = (postOpValueBirth instanceof IdentityMapper.BirthId.Heap)
                                 ? ((IdentityMapper.BirthId.Heap) postOpValueBirth).creatorRoleId : -1;
        int creatorRoles = ((Math.max(0, valueCreatorRole)  & 0xFFFF) << 16)
                         |  (Math.max(0, postOpCreatorRole) & 0xFFFF);

        if (isArray) {
            int packedType = packAtomicArrayType(eventType, receiverBirth.siteId);
            BinarySchema.write(seq, (long) roleId, packedType, receiverBirth.count, index, valueBirth.siteId,
                    valueBirth.count, postOpValueBirth.siteId, postOpValueBirth.count, creatorRoles);
        } else {
            int packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
            BinarySchema.write(seq, (long) roleId, packedType, receiverBirth.siteId, receiverBirth.count,
                    valueBirth.siteId, valueBirth.count, postOpValueBirth.siteId, postOpValueBirth.count, creatorRoles);
        }
    }

    public static void logException(Object exception, int siteId) {
        long tid = Thread.currentThread().threadId();
        int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
        if (roleId == -1) return;

        BirthId birthId = IdentityMapper.getBirthId(exception, null, siteId);
        long seq = nextSeq();
        String className = exception.getClass().getName();
        // System.out.println(String.format(
                // "[THROW]  seq=%d role=%d  %s  site=%d",
                // seq, roleId, className, siteId));

        BinarySchema.write(seq, (long) roleId,
                BinarySchema.packType(BinarySchema.Event.EXCEPTION_THROW, BinarySchema.Flags.NONE),
                birthId.siteId, birthId.count, siteId);
    }

    // Helper method to get event name for sync events
    private static String getEventName(int eventType) {
        switch (eventType) {
            case BinarySchema.Event.MONITOR_ENTER:
                return "MONITOR_ENTER";
            case BinarySchema.Event.MONITOR_EXIT:
                return "MONITOR_EXIT";
            case BinarySchema.Event.THREAD_PARK:
                return "THREAD_PARK";
            case BinarySchema.Event.THREAD_UNPARK:
                return "THREAD_UNPARK";
            case BinarySchema.Event.THREAD_START:
                return "THREAD_START";
            case BinarySchema.Event.THREAD_JOIN:
                return "THREAD_JOIN";
            case BinarySchema.Event.THREAD_INTERRUPT:
                return "THREAD_INTERRUPT";
            case BinarySchema.Event.THREAD_SLEEP:
                return "THREAD_SLEEP";
            case BinarySchema.Event.THREAD_WAKEUP:
                return "THREAD_WAKEUP";
            case BinarySchema.Event.THREAD_YIELD:
                return "THREAD_YIELD";
            case BinarySchema.Event.THREAD_WAIT:
                return "THREAD_WAIT";
            case BinarySchema.Event.THREAD_NOTIFY:
                return "THREAD_NOTIFY";
            case BinarySchema.Event.THREAD_NOTIFY_ALL:
                return "THREAD_NOTIFY_ALL";
            case BinarySchema.Event.THREAD_JOIN_TIMEOUT:
                return "THREAD_JOIN_TIMEOUT";
            case BinarySchema.Event.THREAD_INTERRUPT_CHECK:
                return "THREAD_INTERRUPT_CHECK";
            case BinarySchema.Event.ATOMIC_READ:
                return "ATOMIC_READ";
            case BinarySchema.Event.ATOMIC_WRITE:
                return "ATOMIC_WRITE";
            case BinarySchema.Event.ATOMIC_RMW:
                return "ATOMIC_RMW";
            case BinarySchema.Event.ATOMIC_CAS:
                return "ATOMIC_CAS";
            case BinarySchema.Event.CLASS_INIT_BEGIN:
                return "CLASS_INIT_BEGIN";
            case BinarySchema.Event.CLASS_INIT_END:
                return "CLASS_INIT_END";
            case BinarySchema.Event.EXCEPTION_THROW:
                return "EXCEPTION_THROW";
            default:
                return "UNKNOWN(" + eventType + ")";
        }
    }
}
