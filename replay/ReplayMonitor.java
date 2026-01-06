package replay;

import java.util.concurrent.atomic.AtomicLong;

public class ReplayMonitor {
    private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

    public static void checkSync(Object lock, int eventType, int siteId) {
        if (isInside.get() || lock == null) return;

        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int rawHash = System.identityHashCode(lock);
            
            // Pass the call to the central coordinator
            ReplayCoordinator.awaitTurn(tid, eventType, rawHash, siteId);
            
        } finally {
            isInside.set(false);
        }
    }
}
