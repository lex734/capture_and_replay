import java.io.RandomAccessFile;

public class TraceReader {
  public static void main(String[] args) throws Exception {
    // Must match the schema size
    final int RECORD_SIZE = 24;
        
    try (RandomAccessFile file = new RandomAccessFile("trace.bin", "r")) {
      long fileSize = file.length();
      System.out.println("Reading trace file (" + fileSize + " bytes)...");
      System.out.println("---------------------------------------------------------------");
      System.out.println(String.format("%-10s | %-10s | %-15s | %s", "SEQ", "THREAD_ID", "EVENT", "OBJ_HASH"));
      System.out.println("---------------------------------------------------------------");

      // Loop through the file 24 bytes at a time
      while (file.getFilePointer() < fileSize) {
        long seq = file.readLong();
        long tid = file.readLong();
        int type = file.readInt();
        int hash = file.readInt();

        // Stop reading if we hit empty records (zeros)
        if (seq == 0 && tid == 0 && type == 0) break;
        String eventName = decodeEvent(type);
        System.out.println(String.format("%-10d | %-10d | %-15s | %d", seq, tid, eventName, hash));
      }
    }
  }

    private static String decodeEvent(int type) {
      switch (type) {
        case 1: return "MONITOR_ENTER";
        case 2: return "MONITOR_EXIT";
        case 3: return "THREAD_PARK";
        case 4: return "THREAD_UNPARK";
        default: return "UNKNOWN(" + type + ")";
      }
    }
}
