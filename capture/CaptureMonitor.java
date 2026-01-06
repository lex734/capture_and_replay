package capture;

import common.BinarySchema;
import common.IdentityMapper;
import java.util.concurrent.atomic.AtomicLong;

public class CaptureMonitor {
  private static final AtomicLong clock = new AtomicLong(0);
  private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

  public static void logSync(Object lock, int eventType, int siteId) {
    if (isInside.get() || lock == null) return;
    isInside.set(true);
    try {
        long seq = clock.getAndIncrement();
        
        // Identity is derived from the CODE, not the MEMORY
        int logicalObjectId = IdentityMapper.getLogicalObjectId(siteId);
        int roleId = IdentityMapper.getRoleIdBySite(Thread.currentThread().getId(), siteId);

        System.out.println("[Capture] Seq: " + seq + " | Site: " + siteId + " | Role: " + roleId + " | Type: " + eventType);
        // We still store the eventType (Enter/Exit) and the siteId
        BinarySchema.write(seq, (long)roleId, eventType, logicalObjectId, siteId);
        
    } finally {
        isInside.set(false);
    }
  }
}
