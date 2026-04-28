package capture;

import common.TraceLogger;
import common.BinarySchema;
import common.IdentityMapper;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

public class CaptureMonitor {
  private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);
  private static boolean shouldIgnoreCurrentRole(int siteId) {
    return !IdentityMapper.shouldTraceCurrentThread(siteId);
  }

  public static void logSync(int eventType, Object lock, int siteId) {
    if (isInside.get()) return;
    if (lock == null && eventType != BinarySchema.Event.THREAD_PARK
        && eventType != BinarySchema.Event.THREAD_SLEEP
        && eventType != BinarySchema.Event.THREAD_WAKEUP
        && eventType != BinarySchema.Event.THREAD_YIELD
        && eventType != BinarySchema.Event.CLASS_INIT_BEGIN
        && eventType != BinarySchema.Event.CLASS_INIT_END) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logSync(eventType, lock, siteId);
    } finally {
        isInside.set(false);
    }
  }

  public static void captureThreadStart(Thread thread, int siteId) {
    if (thread == null) return;
    if (shouldIgnoreCurrentRole(siteId)) {
      thread.start();
      return;
    }
    if (isInside.get()) {
      thread.start();
      return;
    }
    isInside.set(true);
    try {
      thread.start();
      TraceLogger.logSync(BinarySchema.Event.THREAD_START, thread, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void captureLock(Lock lock, int siteId) {
    if (lock == null) return;
    if (shouldIgnoreCurrentRole(siteId)) {
      lock.lock();
      return;
    }
    if (isInside.get()) {
      lock.lock();
      return;
    }
    isInside.set(true);
    try {
      lock.lock();
      TraceLogger.logSync(BinarySchema.Event.MONITOR_ENTER, lock, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void captureUnlock(Lock lock, int siteId) {
    if (lock == null) return;
    if (shouldIgnoreCurrentRole(siteId)) {
      lock.unlock();
      return;
    }
    if (isInside.get()) {
      lock.unlock();
      return;
    }
    isInside.set(true);
    try {
      lock.unlock();
      TraceLogger.logSync(BinarySchema.Event.MONITOR_EXIT, lock, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static Condition captureNewCondition(Lock lock, int siteId) {
    if (lock == null) return null;
    Condition condition = lock.newCondition();
    if (condition == null || isInside.get()) return condition;
    if (shouldIgnoreCurrentRole(siteId)) return condition;
    isInside.set(true);
    try {
      IdentityMapper.registerAllocation(condition, siteId);
      return condition;
    } finally {
      isInside.set(false);
    }
  }

  public static void logConditionAwait(Condition condition, int siteId) {
    if (condition == null || isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logSync(BinarySchema.Event.THREAD_WAIT, condition, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logConditionSignal(Condition condition, int siteId) {
    if (condition == null || isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logSync(BinarySchema.Event.THREAD_NOTIFY, condition, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logConditionSignalAll(Condition condition, int siteId) {
    if (condition == null || isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logSync(BinarySchema.Event.THREAD_NOTIFY_ALL, condition, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void captureAwait(Condition condition, int siteId) throws InterruptedException {
    logConditionAwait(condition, siteId);
    condition.await();
  }

  public static void captureAwaitUninterruptibly(Condition condition, int siteId) {
    logConditionAwait(condition, siteId);
    condition.awaitUninterruptibly();
  }

  public static long captureAwaitNanos(Condition condition, long nanosTimeout, int siteId) throws InterruptedException {
    logConditionAwait(condition, siteId);
    return condition.awaitNanos(nanosTimeout);
  }

  public static boolean captureAwaitUntil(Condition condition, java.util.Date deadline, int siteId)
      throws InterruptedException {
    logConditionAwait(condition, siteId);
    return condition.awaitUntil(deadline);
  }

  public static boolean captureAwaitTimed(Condition condition, long time, TimeUnit unit, int siteId)
      throws InterruptedException {
    logConditionAwait(condition, siteId);
    return condition.await(time, unit);
  }

  public static void captureSignal(Condition condition, int siteId) {
    if (condition == null) return;
    if (shouldIgnoreCurrentRole(siteId)) {
      condition.signal();
      return;
    }
    if (isInside.get()) {
      condition.signal();
      return;
    }
    isInside.set(true);
    try {
      condition.signal();
      TraceLogger.logSync(BinarySchema.Event.THREAD_NOTIFY, condition, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void captureSignalAll(Condition condition, int siteId) {
    if (condition == null) return;
    if (shouldIgnoreCurrentRole(siteId)) {
      condition.signalAll();
      return;
    }
    if (isInside.get()) {
      condition.signalAll();
      return;
    }
    isInside.set(true);
    try {
      condition.signalAll();
      TraceLogger.logSync(BinarySchema.Event.THREAD_NOTIFY_ALL, condition, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldInt(int value, int eventType, Object owner, int siteId,
      boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logFieldInt(value, eventType, owner, siteId, isVolatile, isStatic, fieldName, ownerName);
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldLong(long value, int eventType, Object owner, int siteId,
      boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logFieldLong(value, eventType, owner, siteId, isVolatile, isStatic, fieldName, ownerName);
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldObj(Object value, int eventType, Object owner, int siteId,
      boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logFieldObj(value, eventType, owner, siteId, isVolatile, isStatic, fieldName, ownerName);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicInt(int intValue, Object receiver, int index, int eventType, int siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicInt(intValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicLong(long longValue, Object receiver, int index, int eventType, int siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicLong(longValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicObj(Object objValue, Object receiver, int index, int eventType, int siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicObj(objValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logException(Object exception, int siteId) {
    if (isInside.get()) return;
    if (exception == null) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logException(exception, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logArrayInt(int value, int eventType, Object array, int index, int siteId) {
    if (isInside.get() || array == null) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logArrayInt(value, eventType, array, index, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logArrayLong(long value, int eventType, Object array, int index, int siteId) {
    if (isInside.get() || array == null) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logArrayLong(value, eventType, array, index, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logArrayObj(Object value, int eventType, Object array, int index, int siteId) {
    if (isInside.get() || array == null) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logArrayObj(value, eventType, array, index, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logNondetInt(int value, int siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetInt(value, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logNondetFloat(float value, int siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetFloat(value, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logNondetLong(long value, int siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetLong(value, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logNondetDouble(double value, int siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetDouble(value, siteId);
    } finally {
      isInside.set(false);
    }
  }
}
