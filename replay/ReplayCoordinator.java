package replay;

import common.BinarySchema;
import common.IdentityMapper;
import java.nio.MappedByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public class ReplayCoordinator {
    private static MappedByteBuffer buffer;
    private static long totalEvents;
    private static final AtomicLong currentSeq = new AtomicLong(0);
    private static final Object controlLock = new Object();

    public static void init(MappedByteBuffer traceBuffer, long count) {
        buffer = traceBuffer;
        totalEvents = count;
    }

    public static void awaitTurn(long tid, int eventType, int rawHash, int siteId) {
        synchronized (controlLock) {
            while (true) {
                long seq = currentSeq.get();
                if (seq >= totalEvents) return;

                // Read expected values from the 28-byte schema
                int pos = (int) (seq * BinarySchema.RECORD_SIZE);
                long expectedRole = buffer.getLong(pos + 8);
                int expectedType = buffer.getInt(pos + 16);
                int expectedObjId = buffer.getInt(pos + 20);
                int expectedSite = buffer.getInt(pos + 24);

                // 1. Resolve current thread's role based on this site
                int currentRole = IdentityMapper.getRoleIdBySite(tid, siteId);
                
                // 2. Resolve current object's logical ID
                int currentObjId = IdentityMapper.getLogicalObjectId(siteId);

                // THE MATCHING LOGIC
                if (currentRole == (int)expectedRole && 
                    eventType == expectedType && 
                    siteId == expectedSite) {
                    
                    if (System.getProperty("debug.replay") != null) {
                        System.out.println("[Replay] MATCH: Seq " + seq + " Role " + currentRole + " Site " + siteId);
                    }
                    
                    currentSeq.incrementAndGet();
                    controlLock.notifyAll(); // Wake up other threads waiting for their turn
                    return;
                } else {
                    try {
                        // Periodic status for debugging "hangs"
                        controlLock.wait(100); 
                        // EVERY 2 SECONDS, PRINT THE STANDOFF
                        if (System.currentTimeMillis() % 2000 < 100) {
                            System.out.println("\n[Replay Blocked]");
                            System.out.println("  Global Seq: " + seq);
                            System.out.println("  Trace Wants: Role " + expectedRole + ", Type " + expectedType + ", Site " + expectedSite);
                            System.out.println("  This Thread: Role " + currentRole + ", Type " + eventType + ", Site " + siteId + " (TID: " + tid + ")");
                        }
                        if (seq % 10 == 0 && System.currentTimeMillis() % 2000 < 100) {
                             System.out.println("[Replay] Blocked at Seq " + seq + ". Waiting for Role " + expectedRole + " at Site " + expectedSite + ". Current is Role " + currentRole + " at Site " + siteId);
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
