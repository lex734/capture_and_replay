package common;

import java.nio.MappedByteBuffer;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;

public class BinarySchema {
    public static final int RECORD_SIZE = 32;

    // Use an AtomicLong to make recordCount thread-safe and useful
    private static final AtomicLong recordCount = new AtomicLong(0);
    private static MappedByteBuffer buffer;
    private static long maxAllowedEvents;

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
    }

    public static class Flags {
        public static final int NONE = 0;
        public static final int IS_VOLATILE = 1 << 0;
        public static final int IS_STATIC = 1 << 1;
    }

    public static void init(String fileName, long maxEvents) throws Exception {
        maxAllowedEvents = maxEvents;
        long totalSize = maxEvents * RECORD_SIZE;
        try (RandomAccessFile file = new RandomAccessFile(fileName, "rw")) {
            file.setLength(totalSize);
            buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
        }
    }

    public static int packType(int eventId, int flags) {
        return (eventId & 0xFF) | (flags << 8);
    }

    public static void write(long seq, long roleId, int packedType, int objSite, int objCount, int data) {
        if (buffer == null || seq >= maxAllowedEvents) return;

        // Track that a record was written
        recordCount.incrementAndGet();

        // Calculate position based on the global sequence
        int pos = (int) (seq * RECORD_SIZE);

        // Atomic write to the memory-mapped buffer
        buffer.putLong(pos, seq);
        buffer.putLong(pos + 8, roleId);
        buffer.putInt(pos + 16, packedType);
        buffer.putInt(pos + 20, objSite);
        buffer.putInt(pos + 24, objCount);
        buffer.putInt(pos + 28, data);
    }

    // New helper to see how many events were captured
    public static long getRecordedCount() {
        return recordCount.get();
    }
}
