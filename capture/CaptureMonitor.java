package capture;

import common.TraceLogger;
import common.BinarySchema;

public class CaptureMonitor {
  private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

  public static void logSync(int eventType, Object lock, String siteString) {
    if (isInside.get()) return;
    // Allow null lock for events that have no associated object
    // (PARK without blocker, SLEEP, WAKEUP, YIELD, CLASS_INIT)
    if (lock == null && eventType != BinarySchema.Event.THREAD_PARK
        && eventType != BinarySchema.Event.THREAD_SLEEP
        && eventType != BinarySchema.Event.THREAD_WAKEUP
        && eventType != BinarySchema.Event.THREAD_YIELD
        && eventType != BinarySchema.Event.CLASS_INIT_BEGIN
        && eventType != BinarySchema.Event.CLASS_INIT_END) return;
    isInside.set(true);
    try {
      TraceLogger.logSync(eventType, lock, siteString);        
    } finally {
        isInside.set(false);
    }
  }

  public static void logField(int eventType, Object owner, String siteString, boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logField(eventType, owner, siteString, isVolatile, isStatic, fieldName, ownerName);
    } finally {
      isInside.set(false);
    }
  }
  
  public static void logAtomicInt(int returnValue, int eventType, String siteString) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicInt(returnValue, eventType, siteString);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicLong(long returnValue, int eventType, String siteString) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicLong(returnValue, eventType, siteString);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicObj(Object returnValue, int eventType, String siteString) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicObj(returnValue, eventType, siteString);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicVoid(int eventType, String siteString) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicVoid(eventType, siteString);
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
