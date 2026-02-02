package capture;

import common.TraceLogger;

public class CaptureMonitor {
  private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

  public static void logSync(int eventType, Object lock, String siteString) {
    if (isInside.get() || lock == null) return;
    isInside.set(true);
    try {
      TraceLogger.logSync(eventType, lock, siteString);        
    } finally {
        isInside.set(false);
    }
  }

  public static void logField(int eventType, Object owner, String siteString, boolean isVolatile, boolean isStatic, String fieldName) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logField(eventType, owner, siteString, isVolatile, isStatic, fieldName);
    } finally {
      isInside.set(false);
    }
  }
  
  public static void logArray(int eventType, Object array, int index, String siteString) {
    if (isInside.get() || array == null) return;
    isInside.set(true);
    try {
      TraceLogger.logArray(eventType, array, index, siteString);
    } finally {
      isInside.set(false);
    }
  }
}
