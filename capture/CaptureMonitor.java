package capture;

import common.TraceLogger;

public class CaptureMonitor {
  private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

  public static void logSync(int eventType, Object lock, int siteId) {
    if (isInside.get() || lock == null) return;
    isInside.set(true);
    try {
      TraceLogger.logSync(eventType, lock, siteId);        
    } finally {
        isInside.set(false);
    }
  }

  public static void logField(int eventType, Object owner, int fieldId, boolean isVolatile, boolean isStatic, int currentSiteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logField(eventType, owner, currentSiteId, isVolatile, isStatic, fieldId);
    } finally {
      isInside.set(false);
    }
  }
  
  public static void logArray(int eventType, Object array, int index, int currentSiteId) {
    if (isInside.get() || array == null) return;
    isInside.set(true);
    try {
      TraceLogger.logArray(eventType, array, index, currentSiteId);
    } finally {
      isInside.set(false);
    }
  }
}
