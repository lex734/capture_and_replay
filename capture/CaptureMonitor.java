package capture;

import common.TraceLogger;
import common.BinarySchema;

public class CaptureMonitor {
  private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

  public static void logSync(int eventType, Object lock, int siteId) {
    if (isInside.get()) return;
    if (lock == null && eventType != BinarySchema.Event.THREAD_PARK
        && eventType != BinarySchema.Event.THREAD_SLEEP
        && eventType != BinarySchema.Event.THREAD_WAKEUP
        && eventType != BinarySchema.Event.THREAD_YIELD
        && eventType != BinarySchema.Event.CLASS_INIT_BEGIN
        && eventType != BinarySchema.Event.CLASS_INIT_END) return;
    isInside.set(true);
    try {
      TraceLogger.logSync(eventType, lock, siteId);
    } finally {
        isInside.set(false);
    }
  }

  public static void logField(int eventType, Object owner, int siteId, boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logField(eventType, owner, siteId, isVolatile, isStatic, fieldName, ownerName);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicInt(int intValue, Object receiver, int index, int eventType, int siteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicInt(intValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicLong(long longValue, Object receiver, int index, int eventType, int siteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicLong(longValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicObj(Object objValue, Object receiver, int index, int eventType, int siteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicObj(objValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logArray(int eventType, Object array, int index, int siteId) {
    if (isInside.get() || array == null) return;
    isInside.set(true);
    try {
      TraceLogger.logArray(eventType, array, index, siteId);
    } finally {
      isInside.set(false);
    }
  }
}
