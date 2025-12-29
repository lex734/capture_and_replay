package core;

import java.nio.MappedByteBuffer;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

public class BinarySchema {
  public static final int RECORD_SIZE = 24;

  public static final int LOCK_ACQ = 1;
  public static final int LOCK_FREE = 0;

  private static MappedByteBuffer buffer;

  public static void init(String fileName, long maxEvents) throws Exception {
    long totalSize = maxEvents * RECORD_SIZE;
    try (RandomAccessFile file = new RandomAccessFile(fileName, "rw")) {
      file.setLength(totalSize);
      buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
    }
  }

  public static void write(long seq, long tid, int type, int hash) {
    if (buffer == null) return;

    int pos = (int) (seq * RECORD_SIZE);

    buffer.putLong(pos, seq);
    buffer.putLong(pos + 8, tid);
    buffer.putInt(pos + 16, type);
    buffer.putInt(pos + 20, hash);
  }
}
