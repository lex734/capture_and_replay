package common;

import java.nio.MappedByteBuffer;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class BinarySchema {
    public static final int RECORD_SIZE = 40;

    // LongAdder uses per-thread striped counters internally — near-zero contention
    // compared to AtomicLong.incrementAndGet() under high concurrency.
    private static final LongAdder recordCount = new LongAdder();
    private static MappedByteBuffer buffer;
    private static long maxAllowedEvents;

    // ---- Batched write position allocation ----
    // Instead of every event doing a CAS on a shared atomic, each thread reserves
    // BATCH_SIZE slots at once. It then fills those slots locally with zero
    // contention.
    // CAS only happens once per BATCH_SIZE events (64x reduction in atomic ops).
    private static final AtomicLong writePos = new AtomicLong(0);
    private static final int BATCH_SIZE = 64;

    // Per-thread batch state: [0] = batchStart, [1] = remainingSlots
    private static final ThreadLocal<long[]> batchState = ThreadLocal.withInitial(() -> new long[] { -1, 0 });

    public static class Event {
        public static final int MONITOR_ENTER = 1;
        public static final int MONITOR_EXIT = 2;
        public static final int THREAD_PARK = 3;
        public static final int THREAD_UNPARK = 4;
        public static final int FIELD_READ = 5;
        public static final int FIELD_WRITE = 6;
        public static final int ARRAY_READ = 7;
        public static final int ARRAY_WRITE = 8;
        public static final int THREAD_START = 9;
        public static final int THREAD_JOIN = 10;
        public static final int THREAD_INTERRUPT = 11;
        public static final int THREAD_SLEEP = 12;
        public static final int THREAD_WAKEUP = 13;
        public static final int THREAD_YIELD = 14;
        public static final int THREAD_WAIT = 15;
        public static final int THREAD_NOTIFY = 16;
        public static final int THREAD_NOTIFY_ALL = 17;
        public static final int THREAD_JOIN_TIMEOUT = 18;
        public static final int THREAD_INTERRUPT_CHECK = 19;
        public static final int ATOMIC_READ = 20;
        public static final int ATOMIC_WRITE = 21;
        public static final int ATOMIC_RMW = 22;
        public static final int CLASS_INIT_BEGIN = 23;
        public static final int CLASS_INIT_END = 24;
        public static final int EXCEPTION_THROW = 25;
        public static final int ATOMIC_CAS = 26;
        public static final int NONDETERMINISTIC_INT = 27;  // int / boolean / float (stored as raw int bits)
        public static final int NONDETERMINISTIC_LONG = 28; // long / double (stored as raw long bits)
    }

    public static class Flags {
        public static final int NONE = 0;
        public static final int IS_VOLATILE = 1 << 0;
        public static final int IS_STATIC = 1 << 1;
        public static final int IS_ARRAY_ATOMIC = 1 << 2;
        // Set on array field/array access events: siteId packed into upper 16 bits
        // of packedType, objSite = birthId.count, objCount = index, data = value.
        public static final int IS_ARRAY_VALUED = 1 << 3;
    }

    public static void init(String fileName, long maxEvents) throws Exception {
        maxAllowedEvents = maxEvents;
        long totalSize = maxEvents * RECORD_SIZE;
        try (RandomAccessFile file = new RandomAccessFile(fileName, "rw")) {
            file.setLength(0);        // truncate to clear stale data from previous runs
            file.setLength(totalSize); // extend with zero-filled bytes
            buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
        }
        // Ensure dirty mapped pages are flushed to disk even if the JVM exits
        // abruptly (e.g. uncaught exception). The OS does this on Linux anyway,
        // but force() makes it explicit and portable.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (buffer != null) buffer.force();
        }, "trace-flush"));
    }

    public static int packType(int eventId, int flags) {
        return (eventId & 0xFF) | (flags << 8);
    }

    /**
     * Allocates the next write slot from the per-thread batch.
     * When a batch is exhausted, reserves a new batch via a single atomic CAS.
     * This amortizes the atomic contention by BATCH_SIZE (64x reduction).
     */
    private static long allocateSlot() {
        long[] state = batchState.get();
        if (state[1] <= 0) {
            // Reserve a new batch — single CAS, amortized over BATCH_SIZE events
            state[0] = writePos.getAndAdd(BATCH_SIZE);
            state[1] = BATCH_SIZE;
        }
        long slot = state[0] + (BATCH_SIZE - state[1]);
        state[1]--;
        return slot;
    }

    public static void write(long seq, long roleId, int packedType, int objSite, int objCount, int data1, int data2) {
        long slot = allocateSlot();
        if (buffer == null || slot >= maxAllowedEvents)
            return;

        // LongAdder.increment() — near-zero contention (striped counters)
        recordCount.increment();

        // Write at the allocated slot position (not at seq position)
        int pos = (int) (slot * RECORD_SIZE);

        buffer.putLong(pos, seq); // packed epoch<<32 | localSeq
        buffer.putLong(pos + 8, roleId);
        buffer.putInt(pos + 16, packedType);
        buffer.putInt(pos + 20, objSite);
        buffer.putInt(pos + 24, objCount);
        buffer.putInt(pos + 28, data1);
        buffer.putInt(pos + 32, data2);
    }

    public static void write(long seq, long roleId, int packedType, int objSite, int objCount, int data) {
        write(seq, roleId, packedType, objSite, objCount, 0, data);
    }

    // How many events were actually written
    public static long getRecordedCount() {
        return recordCount.sum();
    }

    // Highest allocated slot index (includes potentially unused batch tail slots)
    public static long getHighWaterMark() {
        return writePos.get();
    }
}
