package core;

import java.util.concurrent.atomic.AtomicLong;

public class CaptureMonitor {
  private static final AtomicLong clock = new AtomicLong(0);
  private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

  public static void logSync(Object lock, int eventType) {
    // Prevent recursive loops (if BinarySchema triggers a sync event)
    if (isInside.get()) return;
    
    isInside.set(true);
    try {
        long seq = clock.getAndIncrement();
        long tid = Thread.currentThread().getId();
        int hash = System.identityHashCode(lock);

        BinarySchema.write(seq, tid, eventType, hash);
    } finally {
        isInside.set(false);
    }
  }
}
