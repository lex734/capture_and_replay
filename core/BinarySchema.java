package core;

import java.nio.MappedByteBuffer;
import java.nio.ByteOrder;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;

public class BinarySchema {
  /* Size of each record in bytes
  | Field  | Offset | Size | Description |
  |--------|--------|------|-------------|
  | seq    | 0      | 8    | Global clock|
  | tid    | 8      | 8    |JVM thread ID|
  | event  | 16     | 4    | Sync events |
  | object | 20     | 4    | Identifier  |
  */
  public static final int RECORD_SIZE = 24;

  public static class Event {
    public static final int MONITOR_ENTER = 1;
    public static final int MONITOR_EXIT = 2;
    public static final int THREAD_PARK = 3;
    public static final int THREAD_UNPARK = 4;
    public static final int VOLATILE_READ = 5;
    public static final int VOLATILE_WRITE = 6;
    public static final int THREAD_START = 7;
    public static final int THREAD_JOIN = 8;
  }

  private static MappedByteBuffer buffer;
  private static final AtomicLong storagePointer = new AtomicLong(0);
  private static long maxRecords;

  public static void init(String fileName, long maxEvents) throws Exception {
    maxRecords = maxEvents;
    long totalSize = maxEvents * RECORD_SIZE;

    if (totalSize > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Trace file too large for a single buffer" + "Reduce maxEvents or implement Multi-Buffer.");
    }
    try (RandomAccessFile file = new RandomAccessFile(fileName, "rw")) {
      file.setLength(totalSize);
      buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
      buffer.order(ByteOrder.LITTLE_ENDIAN);
    }
  }

  /**
   * @param logicalSeq The Global Order Vector/Epoch (Partial Order)
   * @param tid        The JVM Thread ID
   * @param eventType  The type of sync event
   * @param hash       The identity hash of the lock object
  */
  public static void write(long logicalSeq, long tid, int eventType, int hash) {
    MappedByteBuffer localBuffer = buffer;

    if (localBuffer == null) return;

    // Get a unique physical slot in the file
    long index = storagePointer.getAndIncrement();
    if (index >= maxRecords) return;

    int pos = (int) (index * RECORD_SIZE);

    // We store the Logical Order Vector in the first 8 bytes of the record
    localBuffer.putLong(pos, logicalSeq); 
    localBuffer.putLong(pos + 8, tid);
    localBuffer.putInt(pos + 16, eventType);
    localBuffer.putInt(pos + 20, hash);
  }

  public static void close() {
    if (buffer != null) {
      buffer.force();
      buffer = null;
    }
  }
}
