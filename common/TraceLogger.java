package common;

import java.util.concurrent.atomic.AtomicLong;
import common.IdentityMapper.BirthId;

public class TraceLogger {
    // ---- Epoch-based sequence tracking (JMM-aware) ----
    //
    // The global epoch only advances on the RELEASE-SIDE of JMM happens-before edges.
    // Acquire-side events and non-synchronization events perform a volatile READ
    // of the epoch — no CAS, no contention.
    // Reduces contention on an overarching global atomic that tracks the sequence
    // of operations. After all, only synchronization events should advance the
    // global sequence number.
    private static final AtomicLong globalEpoch = new AtomicLong(0);

    // Per-thread state: [0] = localSeq, [1] = cachedEpoch
    //   localSeq: incremented on every event (thread-local, zero contention)
    //   cachedEpoch: the global epoch snapshot at the time of the event
    private static final ThreadLocal<long[]> threadState = ThreadLocal.withInitial(() -> new long[]{0, 0});

    /**
     * Returns a packed sequence number: (epoch << 32) | localSeq
     *
     * @param advanceEpoch true only for release-side JMM HB events (CAS on globalEpoch).
     *                     false for acquire-side and non-HB events (volatile read only).
     */
    private static long nextSeq(boolean advanceEpoch) {
        long[] state = threadState.get();
        state[0]++; // thread-local increment — zero contention
        if (advanceEpoch) {
            state[1] = globalEpoch.incrementAndGet();
        } else {
            state[1] = globalEpoch.get(); // volatile read — no CAS
        }
        return (state[1] << 32) | (state[0] & 0xFFFFFFFFL);
    }

    /**
     * Determines whether this event type is the release-side of a JMM happens-before edge.
     * Only release-side events advance the global epoch.
     *
     * JMM 17.4.5 happens-before rules:
     *   unlock(m) HB lock(m)                → MONITOR_EXIT is release
     *   Thread.start() HB first action       → THREAD_START is release
     *   notify/notifyAll HB wait return      → THREAD_NOTIFY[_ALL] is release
     *   unpark(t) HB park() return in t      → THREAD_UNPARK is release
     *   interrupt() HB detection             → THREAD_INTERRUPT is release
     *   thread termination HB join() return  → THREAD_WAKEUP as acquire-side proxy
     *     (thread termination has no explicit event, so WAKEUP after join/wait/park
     *      serves as the epoch boundary for the acquiring thread)
     *   end of <clinit> HB subsequent use    → CLASS_INIT_END is release
     *   atomic write/RMW HB atomic read      → ATOMIC_WRITE, ATOMIC_RMW are release
     *   volatile write HB volatile read      → handled in logField via isVolatile flag
     */
    private static boolean isHBRelease(int eventType) {
        switch (eventType) {
            case BinarySchema.Event.MONITOR_EXIT:
            case BinarySchema.Event.THREAD_START:
            case BinarySchema.Event.THREAD_NOTIFY:
            case BinarySchema.Event.THREAD_NOTIFY_ALL:
            case BinarySchema.Event.THREAD_UNPARK:
            case BinarySchema.Event.THREAD_INTERRUPT:
            case BinarySchema.Event.THREAD_WAKEUP:
            case BinarySchema.Event.CLASS_INIT_END:
            case BinarySchema.Event.ATOMIC_WRITE:
            case BinarySchema.Event.ATOMIC_RMW:
                return true;
            default:
                return false;
        }
    }

    // for synchronization events such as MONITOR_ENTER, MONITOR_EXIT
    public static void logSync(int eventType, Object lock, String siteString) {
        long seq = nextSeq(isHBRelease(eventType));
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId birthId = IdentityMapper.getBirthId(lock, null, currentSiteId);
        String eventName = getEventName(eventType);
        System.out.println(String.format("[SYNC] epoch=%d, localSeq=%d, thread=%d, roleId=%d, event=%s(%d), lock=%s, lockSiteId=%d, lockCount=%d, siteId=%d",
            seq >>> 32, seq & 0xFFFFFFFFL, tid, roleId, eventName, eventType,
            lock != null ? lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)) : "null",
            birthId.siteId, birthId.count, currentSiteId));
        BinarySchema.write(seq, (long)roleId, ((eventType & 0xFF) | (BinarySchema.Flags.NONE << 8)), birthId.siteId, birthId.count, currentSiteId);
    }

    public static void logField(int eventType, Object owner, String siteString, boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
        // JMM 17.4.5: only volatile WRITES are release-side HB.
        // Volatile READS are acquire-side — just read the epoch.
        // Non-volatile accesses (including static field reads and writes) have no HB significance.
        boolean advanceEpoch = isVolatile && (eventType == BinarySchema.Event.FIELD_WRITE);
        long seq = nextSeq(advanceEpoch);
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
        // System.out.println(String.format("isVolatile: %b", isVolatile));

        int packedType = BinarySchema.packType(eventType, flags);
    
        // Logging for visibility (Useful for debugging the deterministic interleaving)
        String eventName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";
        System.out.println(String.format("[OBJECT-COORD] epoch=%d, localSeq=%d, role=%d, event=%s, field=%s.%s, fieldId=%d", 
            seq >>> 32, seq & 0xFFFFFFFFL, roleId, eventName, ownerName, fieldName, fieldId));
    
        // 6. Write to Binary Log
        // Data field contains the fieldId (the unique coordinate for this variable)
        BinarySchema.write(seq, (long)roleId, packedType, birthId.siteId, birthId.count, fieldId);
    }

    // for array access events
    public static void logArray(int eventType, Object array, int index, String siteString) {
        long seq = nextSeq(false); // array access — no JMM HB significance
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);

        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
        
        String eventName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";
        System.out.println(String.format("[ARRAY] epoch=%d, localSeq=%d, thread=%d, roleId=%d, event=%s(%d), array=%s, arraySiteId=%d, arrayCount=%d, index=%d, siteId=%d",
            seq >>> 32, seq & 0xFFFFFFFFL, tid, roleId, eventName, eventType,
            array != null ? array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)) : "null",
            birthId.siteId, birthId.count, index, currentSiteId));

        BinarySchema.write(seq, roleId, ((eventType & 0xFF) | (BinarySchema.Flags.NONE << 8)), birthId.siteId, birthId.count, index);
    }

    // ---- Unified atomic loggers ----
    // All atomic operations capture WHERE (receiver + index) and WHAT (value).
    //
    // Binary record layout — ARRAY atomics (IS_ARRAY_ATOMIC flag set):
    //   packedType upper 16 bits = receiver BirthId.siteId  (which allocation site)
    //   objSite  = receiver BirthId.count                   (which instance)
    //   objCount = array index                              (WHERE within the array)
    //   data     = value                                    (WHAT was written/read)
    //
    // Binary record layout — SCALAR atomics (no flag):
    //   objSite  = receiver BirthId.siteId
    //   objCount = receiver BirthId.count
    //   data     = value (int, lo-32-of-long, or value BirthId.siteId for Object)

    private static int packAtomicArrayType(int eventType, int receiverSiteId) {
        return (eventType & 0xFF)
             | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8)
             | ((receiverSiteId & 0xFFFF) << 16);
    }

    /**
     * Atomic logger for int-sized values (int, boolean, byte, short, char).
     * @param intValue  the written value (void set) or return value (get/RMW)
     * @param receiver  the atomic object instance (AtomicInteger, AtomicIntegerArray, etc.)
     * @param index     array element index, or -1 for scalar atomics
     */
    public static void logAtomicInt(int intValue, Object receiver, int index, int eventType, String siteString) {
        long seq = nextSeq(isHBRelease(eventType));
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId birthId = IdentityMapper.getBirthId(receiver, null, currentSiteId);
        boolean isArray = (index >= 0);

        String eventName = getEventName(eventType);
        String receiverStr = receiver != null
            ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver))
            : "null";
        if (isArray) {
            System.out.println(String.format(
                "[ATOMIC] epoch=%d, localSeq=%d, thread=%d, roleId=%d, event=%s(%d), receiver=%s, index=%d, value=%d, siteId=%d",
                seq >>> 32, seq & 0xFFFFFFFFL, tid, roleId, eventName, eventType, receiverStr, index, intValue, currentSiteId));
        } else {
            System.out.println(String.format(
                "[ATOMIC] epoch=%d, localSeq=%d, thread=%d, roleId=%d, event=%s(%d), receiver=%s, value=%d, siteId=%d",
                seq >>> 32, seq & 0xFFFFFFFFL, tid, roleId, eventName, eventType, receiverStr, intValue, currentSiteId));
        }

        if (isArray) {
            int packedType = packAtomicArrayType(eventType, birthId.siteId);
            BinarySchema.write(seq, (long)roleId, packedType, birthId.count, index, intValue);
        } else {
            int packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
            BinarySchema.write(seq, (long)roleId, packedType, birthId.siteId, birthId.count, intValue);
        }
    }

    /**
     * Atomic logger for long values.
     * Array atomics: stores index + low 32 bits of long value.
     * Scalar atomics: stores full receiver BirthId + low 32 bits of long value.
     */
    public static void logAtomicLong(long longValue, Object receiver, int index, int eventType, String siteString) {
        long seq = nextSeq(isHBRelease(eventType));
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId birthId = IdentityMapper.getBirthId(receiver, null, currentSiteId);
        boolean isArray = (index >= 0);

        String eventName = getEventName(eventType);
        String receiverStr = receiver != null
            ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver))
            : "null";
        if (isArray) {
            System.out.println(String.format(
                "[ATOMIC] epoch=%d, localSeq=%d, thread=%d, roleId=%d, event=%s(%d), receiver=%s, index=%d, value=%d, siteId=%d",
                seq >>> 32, seq & 0xFFFFFFFFL, tid, roleId, eventName, eventType, receiverStr, index, longValue, currentSiteId));
        } else {
            System.out.println(String.format(
                "[ATOMIC] epoch=%d, localSeq=%d, thread=%d, roleId=%d, event=%s(%d), receiver=%s, value=%d, siteId=%d",
                seq >>> 32, seq & 0xFFFFFFFFL, tid, roleId, eventName, eventType, receiverStr, longValue, currentSiteId));
        }

        if (isArray) {
            int packedType = packAtomicArrayType(eventType, birthId.siteId);
            BinarySchema.write(seq, (long)roleId, packedType, birthId.count, index, (int)longValue);
        } else {
            int packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
            BinarySchema.write(seq, (long)roleId, packedType, birthId.siteId, birthId.count, (int)longValue);
        }
    }

    /**
     * Atomic logger for Object values (AtomicReference, AtomicReferenceArray).
     * Array atomics: stores index + value's BirthId.siteId.
     * Scalar atomics: stores receiver BirthId + value's BirthId.siteId.
     */
    public static void logAtomicObj(Object objValue, Object receiver, int index, int eventType, String siteString) {
        long seq = nextSeq(isHBRelease(eventType));
        long tid = Thread.currentThread().getId();
        int currentSiteId = IdentityMapper.getSiteId(siteString);
        int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
        BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
        BirthId valueBirth = IdentityMapper.getBirthId(objValue, null, currentSiteId);
        boolean isArray = (index >= 0);

        String eventName = getEventName(eventType);
        String receiverStr = receiver != null
            ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver))
            : "null";
        String valueStr = objValue != null
            ? objValue.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(objValue))
            : "null";
        if (isArray) {
            System.out.println(String.format(
                "[ATOMIC] epoch=%d, localSeq=%d, thread=%d, roleId=%d, event=%s(%d), receiver=%s, index=%d, value=%s, siteId=%d",
                seq >>> 32, seq & 0xFFFFFFFFL, tid, roleId, eventName, eventType, receiverStr, index, valueStr, currentSiteId));
        } else {
            System.out.println(String.format(
                "[ATOMIC] epoch=%d, localSeq=%d, thread=%d, roleId=%d, event=%s(%d), receiver=%s, value=%s, siteId=%d",
                seq >>> 32, seq & 0xFFFFFFFFL, tid, roleId, eventName, eventType, receiverStr, valueStr, currentSiteId));
        }

        if (isArray) {
            int packedType = packAtomicArrayType(eventType, receiverBirth.siteId);
            BinarySchema.write(seq, (long)roleId, packedType, receiverBirth.count, index, valueBirth.siteId);
        } else {
            int packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
            BinarySchema.write(seq, (long)roleId, packedType, receiverBirth.siteId, receiverBirth.count, valueBirth.siteId);
        }
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
