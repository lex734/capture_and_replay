package core;

import java.util.concurrent.atomic.AtomicLong;

public class CaptureMonitor {
  private static final AtomicLong globalEpoch = new AtomicLong(0);

  private static final ThreadLocal<Long> localStep = ThreadLocal.withInitial(() -> 0L);
  private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial((() -> false);

  public static void logSync(Object lock, int eventType) {
    // Prevent recursive loops (if BinarySchema triggers a sync event)
    if (isInside.get()) return;
    
    isInside.set(true);
    try {
        long currentStep = localStep.get() + 1;
        localStep.set(currentStep);

        long epoch = globalEpoch.get();

        long logicalSeq = (epoch << 32) | (currentStep & 0xFFFFFFFFL);

        long tid = Thread.currentThread().getId();
        int hash = (lock != null) ? System.identityHashCode(lock) : 0;

        BinarySchema.write(logicalSeq, tid, eventType, hash);

        if (isHardSync(eventType)) {
          globalEpoch.incrementAndGet();
          localStep.set(0L);
        }
    } catch (Throwable t) {
    } finally {
        isInside.set(false);
    }
  }

private static boolean isHardSync(int type) {
    return type == Event.MONITOR_EXIT   || // Release
           type == Event.VOLATILE_WRITE || // Release
           type == Event.THREAD_UNPARK  || // Signal
           type == Event.THREAD_START;     // Release (Parent to Child)
}
}
