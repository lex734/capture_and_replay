package replay;

import common.BinarySchema;

import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class ReplayCoordinator {
    // Pre-sorted event array: each element is [seq, roleId, packedType, objSite, objCount, data]
    private static long[][] sortedEvents;
    private static long totalEvents;
    private static final AtomicLong currentIdx = new AtomicLong(0);
    private static final Object controlLock = new Object();

    public static void init(MappedByteBuffer traceBuffer, long count) {
        // Read all non-empty records from the buffer.
        // With batched slot allocation, records may not be contiguous —
        // zero-filled gaps exist where batch tails were unused.
        ArrayList<long[]> events = new ArrayList<>();
        long maxSlots = traceBuffer.capacity() / BinarySchema.RECORD_SIZE;

        for (long i = 0; i < maxSlots; i++) {
            int pos = (int)(i * BinarySchema.RECORD_SIZE);
            long seq       = traceBuffer.getLong(pos);
            long roleId    = traceBuffer.getLong(pos + 8);
            int packedType = traceBuffer.getInt(pos + 16);
            int objSite    = traceBuffer.getInt(pos + 20);
            int objCount   = traceBuffer.getInt(pos + 24);
            int data       = traceBuffer.getInt(pos + 28);

            // Skip zero-filled slots (unused batch tails)
            if (seq == 0 && roleId == 0 && packedType == 0) continue;

            events.add(new long[]{seq, roleId, packedType, objSite, objCount, data});
        }

        // Sort by packed seq (epoch << 32 | localSeq) to reconstruct causal order
        events.sort((a, b) -> Long.compare(a[0], b[0]));

        sortedEvents = events.toArray(new long[0][]);
        totalEvents = sortedEvents.length;

        System.out.println("[ReplayCoordinator] Loaded and sorted " + totalEvents + " events.");
    }

    /**
     * Blocks the calling thread until its event matches the next expected event
     * in the captured total order.
     *
     * Parameters mirror the fields written by BinarySchema.write() during capture
     * (minus seq, which is only used for ordering):
     *
     * @param roleId     Resolved role ID for the current thread
     * @param packedType Event type + flags (same packing as BinarySchema)
     * @param objSite    Birth site of the target object (lock, field owner, array)
     * @param objCount   Birth count of the target object
     * @param data       Event-specific payload (siteId for sync, fieldId for fields,
     *                   array index for arrays, return value / siteId for atomics)
     */
    public static void awaitTurn(int roleId, int packedType, int objSite, int objCount, int data) {
        synchronized (controlLock) {
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents) return;

                long[] expected = sortedEvents[(int)idx];
                int expectedRole     = (int)expected[1];
                int expectedType     = (int)expected[2];
                int expectedObjSite  = (int)expected[3];
                int expectedObjCount = (int)expected[4];
                int expectedData     = (int)expected[5];

                // Match on role + event type.  These two fields are deterministic
                // (computed from thread discovery order and code location) and
                // sufficient to identify which thread may proceed next.
                if (roleId == expectedRole && packedType == expectedType) {

                    if (System.getProperty("debug.replay") != null) {
                        long seq = expected[0];
                        System.out.println("[Replay] MATCH idx=" + idx +
                            " epoch=" + (seq >>> 32) + " localSeq=" + (seq & 0xFFFFFFFFL) +
                            " role=" + roleId + " type=" + packedType +
                            " obj=(" + objSite + "," + objCount + ") data=" + data);
                    }

                    currentIdx.incrementAndGet();
                    controlLock.notifyAll();
                    return;
                } else {
                    try {
                        controlLock.wait(100);
                        // Periodic status for debugging hangs
                        if (System.currentTimeMillis() % 2000 < 100) {
                            long seq = expected[0];
                            System.out.println("\n[Replay Blocked]");
                            System.out.println("  Global Idx: " + idx +
                                " (Epoch " + (seq >>> 32) + ", LocalSeq " + (seq & 0xFFFFFFFFL) + ")");
                            System.out.println("  Trace Wants: Role=" + expectedRole +
                                " Type=" + expectedType +
                                " Obj=(" + expectedObjSite + "," + expectedObjCount + ")" +
                                " Data=" + expectedData);
                            System.out.println("  This Thread: Role=" + roleId +
                                " Type=" + packedType +
                                " Obj=(" + objSite + "," + objCount + ")" +
                                " Data=" + data);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
