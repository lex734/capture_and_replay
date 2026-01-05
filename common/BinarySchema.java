package common;

import java.nio.MappedByteBuffer;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

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
  }

  private static MappedByteBuffer buffer;

  public static void init(String fileName, long maxEvents) throws Exception {
    long totalSize = maxEvents * RECORD_SIZE;
    try (RandomAccessFile file = new RandomAccessFile(fileName, "rw")) {
      file.setLength(totalSize);
      buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
    }
  }

  public static void write(long seq, long tid, int eventType, int hash) {
    if (buffer == null) return;

    int pos = (int) (seq * RECORD_SIZE);

    buffer.putLong(pos, seq);
    buffer.putLong(pos + 8, tid);
    buffer.putInt(pos + 16, eventType);
    buffer.putInt(pos + 20, hash);
  }
}
