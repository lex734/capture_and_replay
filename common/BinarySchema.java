package common;

import java.nio.MappedByteBuffer;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

public class BinarySchema {
  /* Size of each record in bytes
   * | Offset | Size | Field     | Description                                      |
   * |--------|------|-----------|--------------------------------------------------|
   * | 0      | 8    | seq       | Global Atomic Sequence (The "Clock")             |
   * | 8      | 8    | roleId    | Logical Role ID (Mapped from raw Thread ID)     |
   * | 16     | 4    | typeFlags | Event Type (8 bits) + Flags (24 bits)            |
   * | 20     | 4    | objSite   | Birth Site: Where this object was first seen    |
   * | 24     | 4    | objCount  | Birth Count: N-th object seen at that site      |
   * | 28     | 4    | data      | Polymorphic: FieldID, Array Index, or SiteID    |
   */
  public static final int RECORD_SIZE = 32; // 8 + 8 + 4 + 4 + 4 + 4

  public static class Event {
    public static final int MONITOR_ENTER = 1;
    public static final int MONITOR_EXIT = 2;
    public static final int THREAD_PARK = 3;
    public static final int THREAD_UNPARK = 4;
    public static final int FIELD_READ = 5;
    public static final int FIELD_WRITE = 6;
    public static final int ARRAY_READ = 7;
    public static final int ARRAY_WRITE = 8;
  }

  public static class Flags {
    public static final int NONE = 0;
    public static final int IS_VOLATILE = 1 << 0;
    public static final int IS_STATIC = 1 << 1;
  }

  private static MappedByteBuffer buffer;

  public static void init(String fileName, long maxEvents) throws Exception {
    long totalSize = maxEvents * RECORD_SIZE;
    try (RandomAccessFile file = new RandomAccessFile(fileName, "rw")) {
      file.setLength(totalSize);
      buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
    }
  }

  public static void write(long seq, long roleId, int typeFlags, int objSite, int objCount, int data) {
    if (buffer == null) return;

    int pos = (int) (seq * RECORD_SIZE);

    buffer.putLong(pos, seq);
    buffer.putLong(pos + 8, roleId);
    buffer.putInt(pos + 16, typeFlags);
    buffer.putInt(pos + 20, objSite);
    buffer.putInt(pos + 24, objCount);
    buffer.putInt(pos + 28, data);
  }
}
