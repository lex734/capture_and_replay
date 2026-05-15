package common;

/**
 * Shared capture/replay semantics for causal ordering.
 */
public final class TraceSemantics {
    private TraceSemantics() {}

    public static boolean advancesEpoch(int packedType) {
        int eventType = packedType & 0xFF;
        int flags = (packedType >> 8) & 0xFF;
        boolean isVolatile = (flags & BinarySchema.Flags.IS_VOLATILE) != 0;

        return eventType == BinarySchema.Event.MONITOR_EXIT
                || eventType == BinarySchema.Event.THREAD_START
                || eventType == BinarySchema.Event.THREAD_NOTIFY
                || eventType == BinarySchema.Event.THREAD_NOTIFY_ALL
                || eventType == BinarySchema.Event.THREAD_UNPARK
                || eventType == BinarySchema.Event.THREAD_INTERRUPT
                || eventType == BinarySchema.Event.THREAD_WAKEUP
                || eventType == BinarySchema.Event.CLASS_INIT_END
                || eventType == BinarySchema.Event.ATOMIC_WRITE
                || eventType == BinarySchema.Event.ATOMIC_RMW
                || eventType == BinarySchema.Event.ATOMIC_CAS
                || (eventType == BinarySchema.Event.FIELD_WRITE && isVolatile);
    }

    public static boolean advancesEpochForEventType(int eventType) {
        return advancesEpoch(BinarySchema.packType(eventType, BinarySchema.Flags.NONE));
    }

    public static boolean isReplayBoundary(int packedType) {
        int eventType = packedType & 0xFF;
        if (usesOccurrenceCounterForEventType(eventType)) return false;
        if (advancesEpoch(packedType)) return true;

        return eventType == BinarySchema.Event.MONITOR_ENTER
                || eventType == BinarySchema.Event.THREAD_PARK
                || eventType == BinarySchema.Event.THREAD_JOIN
                || eventType == BinarySchema.Event.THREAD_SLEEP
                || eventType == BinarySchema.Event.THREAD_YIELD
                || eventType == BinarySchema.Event.THREAD_WAIT
                || eventType == BinarySchema.Event.THREAD_JOIN_TIMEOUT
                || eventType == BinarySchema.Event.THREAD_INTERRUPT_CHECK
                || eventType == BinarySchema.Event.CLASS_INIT_BEGIN;
    }

    public static boolean isReplayBoundaryForEventType(int eventType) {
        return isReplayBoundary(BinarySchema.packType(eventType, BinarySchema.Flags.NONE));
    }

    public static boolean usesOccurrenceCounterForEventType(int eventType) {
        return eventType == BinarySchema.Event.FIELD_WRITE
                || eventType == BinarySchema.Event.ATOMIC_WRITE
                || eventType == BinarySchema.Event.ATOMIC_RMW
                || eventType == BinarySchema.Event.ATOMIC_CAS;
    }

    public static int packFieldType(int eventType, boolean isVolatile, boolean isStatic) {
        int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                  | (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
        return BinarySchema.packType(eventType, flags);
    }
}
