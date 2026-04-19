package capture;

import common.TraceLogger;
import common.BinarySchema;
import common.IdentityMapper;
import common.IdentityMapper.BirthId;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CaptureMonitor {
  private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

  // Single global ReentrantLock that serialises ALL observable operations
  // (field reads/writes and atomic ops) with their sequence-number assignment.
  //
  // ReentrantLock (rather than a plain monitor) is required for atomic ops:
  // beginAtomicCapture() acquires the lock, the original atomic method call
  // executes in instrumented bytecode while the lock is held, then
  // endAtomicCapture*() assigns seq + writes the trace record + releases.
  // A synchronized block cannot span method boundaries.
  //
  // Field ops use the same lock so fields and atomics share one total order.
  static final ReentrantLock captureOrderLock = new ReentrantLock();

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

  public static void logAtomicInt(int intValue, int postOpValue, Object receiver, int index, int eventType, int siteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicInt(intValue, postOpValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicLong(long longValue, long postOpValue, Object receiver, int index, int eventType, int siteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicLong(longValue, postOpValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicObj(Object objValue, Object postOpValue, Object receiver, int index, int eventType, int siteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicObj(objValue, postOpValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logException(Object exception, int siteId) {
    if (isInside.get()) return;
    if (exception == null) return;
    isInside.set(true);
    try {
      TraceLogger.logException(exception, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logArray(int eventType, Object array, int index, int siteId) {
    if (isInside.get() || array == null) return;
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
      if (roleId == -1) {
        return;
      }

      // Acquire the global capture-order lock so that TraceLogger.nextSeq()
      // (called inside TraceLogger.logArray) executes while we hold the lock.
      // This serialises array accesses with other observable operations.
      captureOrderLock.lock();
      try {
        TraceLogger.logArray(eventType, array, index, siteId);
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  public static Condition captureNewCondition(Lock lock, int siteId) {
    if (lock == null) return null;
    Condition condition = lock.newCondition();
    if (condition == null || isInside.get()) return condition;
    isInside.set(true);
    try {
      // Register the condition at its creation site so capture and replay
      // resolve the same BirthId for later await/signal operations.
      IdentityMapper.registerAllocation(condition, siteId);
      return condition;
    } finally {
      isInside.set(false);
    }
  }

  // ---- Atomic capture bracketing -----------------------------------------------
  // beginAtomicCapture() acquires captureOrderLock.  The original atomic
  // method call then executes in the instrumented bytecode while the lock is
  // held.  endAtomicCapture*() assigns the seq, writes the trace record, and
  // releases the lock — all atomically with the operation that just completed.

  public static void beginAtomicCapture() {
    captureOrderLock.lock();
  }

  public static void endAtomicCaptureInt(int returnValue, Object receiver, int index, int eventType, int siteId) {
    try {
      isInside.set(true);
      try {
        // For RMW ops the return value may differ from the post-operation cell value
        // (e.g. getAndIncrement returns old; cell holds old+1).  Read the cell now,
        // while captureOrderLock is still held, so no other thread can change it.
        int postOpValue = returnValue;
        if (eventType == common.BinarySchema.Event.ATOMIC_RMW
                || eventType == common.BinarySchema.Event.ATOMIC_CAS) {
          if (index >= 0) {
            postOpValue = ((java.util.concurrent.atomic.AtomicIntegerArray) receiver).get(index);
          } else {
            postOpValue = ((java.util.concurrent.atomic.AtomicInteger) receiver).get();
          }
        }
        TraceLogger.logAtomicInt(returnValue, postOpValue, receiver, index, eventType, siteId);
      } finally { isInside.set(false); }
    } finally {
      captureOrderLock.unlock();
    }
  }

  public static void endAtomicCaptureLong(long returnValue, Object receiver, int index, int eventType, int siteId) {
    try {
      isInside.set(true);
      try { 
        long postOpValue = returnValue;
        if (eventType == common.BinarySchema.Event.ATOMIC_RMW
                || eventType == common.BinarySchema.Event.ATOMIC_CAS) {
          if (index >= 0) {
            postOpValue = ((java.util.concurrent.atomic.AtomicLongArray) receiver).get(index);
          } else {
            postOpValue = ((java.util.concurrent.atomic.AtomicLong) receiver).get();
          }
        }
        TraceLogger.logAtomicLong(returnValue, postOpValue, receiver, index, eventType, siteId); }
      finally { isInside.set(false); }
    } finally {
      captureOrderLock.unlock();
    }
  }

  public static void endAtomicCaptureObj(Object returnValue, Object receiver, int index, int eventType, int siteId) {
    try {
      isInside.set(true);
      try { 
        Object postOpValue = returnValue;
        if (eventType == common.BinarySchema.Event.ATOMIC_RMW
                || eventType == common.BinarySchema.Event.ATOMIC_CAS) {
          if (index >= 0) {
            postOpValue = ((java.util.concurrent.atomic.AtomicReferenceArray<?>) receiver).get(index);
          } else {
            postOpValue = ((java.util.concurrent.atomic.AtomicReference<?>) receiver).get();
          }
        }
        TraceLogger.logAtomicObj(returnValue, postOpValue, receiver, index, eventType, siteId);
      } finally { isInside.set(false); }
    } finally {
      captureOrderLock.unlock();
    }
  }

  // ---- Typed field-read methods -----------------------------------------------
  // These replace the GETFIELD/GETSTATIC bytecode entirely: captureOrderLock is
  // held across both the actual read (via reflection) and the seq assignment so
  // the recorded seq faithfully reflects when the read occurred.

  public static int logFieldReadInt(Object owner, int currentSiteId,
                                    boolean isVolatile, boolean isStatic,
                                    String fieldName, String ownerName) {
    if (isInside.get()) {
      try { return findField(ownerName, fieldName).getInt(owner); }
      catch (ReflectiveOperationException e) { return 0; }
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try { return findField(ownerName, fieldName).getInt(owner); }
        catch (ReflectiveOperationException e) { return 0; }
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      int fieldId   = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags     = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                    | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

      int[] result = new int[1];
      captureOrderLock.lock();
      try {
        try {
          java.lang.reflect.Field f = findField(ownerName, fieldName);
          Class<?> t = f.getType();
          if      (t == int.class)     result[0] = f.getInt(owner);
          else if (t == boolean.class) result[0] = f.getBoolean(owner) ? 1 : 0;
          else if (t == byte.class)    result[0] = f.getByte(owner);
          else if (t == short.class)   result[0] = f.getShort(owner);
          else if (t == char.class)    result[0] = f.getChar(owner);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
        // System.out.println(String.format(
            // "[FIELD]  seq=%d role=%d  READ%s %s.%s = %d  fieldId=%d",
            // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
      } finally {
        captureOrderLock.unlock();
      }
      return result[0];
    } finally {
      isInside.set(false);
    }
  }

  public static float logFieldReadFloat(Object owner, int currentSiteId,
                                        boolean isVolatile, boolean isStatic,
                                        String fieldName, String ownerName) {
    if (isInside.get()) {
      try { return findField(ownerName, fieldName).getFloat(owner); }
      catch (ReflectiveOperationException e) { return 0f; }
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try { return findField(ownerName, fieldName).getFloat(owner); }
        catch (ReflectiveOperationException e) { return 0f; }
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

      float[] result = new float[1];
      captureOrderLock.lock();
      try {
        try { result[0] = findField(ownerName, fieldName).getFloat(owner); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
        // System.out.println(String.format(
            // "[FIELD]  seq=%d role=%d  READ%s %s.%s = %f  fieldId=%d",
            // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
      } finally {
        captureOrderLock.unlock();
      }
      return result[0];
    } finally {
      isInside.set(false);
    }
  }

  public static long logFieldReadLong(Object owner, int currentSiteId,
                                      boolean isVolatile, boolean isStatic,
                                      String fieldName, String ownerName) {
    if (isInside.get()) {
      try { return findField(ownerName, fieldName).getLong(owner); }
      catch (ReflectiveOperationException e) { return 0L; }
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try { return findField(ownerName, fieldName).getLong(owner); }
        catch (ReflectiveOperationException e) { return 0L; }
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

      long[] result = new long[1];
      captureOrderLock.lock();
      try {
        try { result[0] = findField(ownerName, fieldName).getLong(owner); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
        // System.out.println(String.format(
            // "[FIELD]  seq=%d role=%d  READ%s %s.%s = %dL  fieldId=%d",
            // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
      } finally {
        captureOrderLock.unlock();
      }
      return result[0];
    } finally {
      isInside.set(false);
    }
  }

  public static double logFieldReadDouble(Object owner, int currentSiteId,
                                          boolean isVolatile, boolean isStatic,
                                          String fieldName, String ownerName) {
    if (isInside.get()) {
      try { return findField(ownerName, fieldName).getDouble(owner); }
      catch (ReflectiveOperationException e) { return 0.0; }
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try { return findField(ownerName, fieldName).getDouble(owner); }
        catch (ReflectiveOperationException e) { return 0.0; }
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

      double[] result = new double[1];
      captureOrderLock.lock();
      try {
        try { result[0] = findField(ownerName, fieldName).getDouble(owner); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
        // System.out.println(String.format(
            // "[FIELD]  seq=%d role=%d  READ%s %s.%s = %f  fieldId=%d",
            // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
      } finally {
        captureOrderLock.unlock();
      }
      return result[0];
    } finally {
      isInside.set(false);
    }
  }

  public static Object logFieldReadObj(Object owner, int currentSiteId,
                                       boolean isVolatile, boolean isStatic,
                                       String fieldName, String ownerName) {
    if (isInside.get()) {
      try { return findField(ownerName, fieldName).get(owner); }
      catch (ReflectiveOperationException e) { return null; }
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try { return findField(ownerName, fieldName).get(owner); }
        catch (ReflectiveOperationException e) { return null; }
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

      Object[] result = new Object[1];
      captureOrderLock.lock();
      try {
        try { result[0] = findField(ownerName, fieldName).get(owner); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
        // System.out.println(String.format(
            // "[FIELD]  seq=%d role=%d  READ%s %s.%s = %s  fieldId=%d",
            // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName,
            // result[0] != null ? result[0].getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(result[0])) : "null",
            // fieldId));
      } finally {
        captureOrderLock.unlock();
      }
      return result[0];
    } finally {
      isInside.set(false);
    }
  }

  // ---- Typed field-write methods -----------------------------------------------
  // Mirror of the read methods: captureOrderLock held across actual write + seq.

  public static void logFieldWriteInt(int value, Object owner, int currentSiteId,
                                      boolean isVolatile, boolean isStatic,
                                      String fieldName, String ownerName) {
    if (isInside.get()) {
      try {
        java.lang.reflect.Field f = findField(ownerName, fieldName);
        Class<?> t = f.getType();
        if      (t == int.class)     f.setInt(owner, value);
        else if (t == boolean.class) f.setBoolean(owner, value != 0);
        else if (t == byte.class)    f.setByte(owner, (byte) value);
        else if (t == short.class)   f.setShort(owner, (short) value);
        else if (t == char.class)    f.setChar(owner, (char) value);
      } catch (ReflectiveOperationException ignored) {}
      return;
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try {
          java.lang.reflect.Field f = findField(ownerName, fieldName);
          Class<?> t = f.getType();
          if      (t == int.class)     f.setInt(owner, value);
          else if (t == boolean.class) f.setBoolean(owner, value != 0);
          else if (t == byte.class)    f.setByte(owner, (byte) value);
          else if (t == short.class)   f.setShort(owner, (short) value);
          else if (t == char.class)    f.setChar(owner, (char) value);
        } catch (ReflectiveOperationException ignored) {}
        return;
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

      captureOrderLock.lock();
      try {
        try {
          java.lang.reflect.Field f = findField(ownerName, fieldName);
          Class<?> t = f.getType();
          if      (t == int.class)     f.setInt(owner, value);
          else if (t == boolean.class) f.setBoolean(owner, value != 0);
          else if (t == byte.class)    f.setByte(owner, (byte) value);
          else if (t == short.class)   f.setShort(owner, (short) value);
          else if (t == char.class)    f.setChar(owner, (char) value);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
        // System.out.println(String.format(
            // "[FIELD]  seq=%d role=%d  WRITE%s %s.%s = %d  fieldId=%d",
            // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldWriteFloat(float value, Object owner, int currentSiteId,
                                        boolean isVolatile, boolean isStatic,
                                        String fieldName, String ownerName) {
    if (isInside.get()) {
      try { findField(ownerName, fieldName).setFloat(owner, value); }
      catch (ReflectiveOperationException ignored) {}
      return;
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try { findField(ownerName, fieldName).setFloat(owner, value); }
        catch (ReflectiveOperationException ignored) {}
        return;
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

      captureOrderLock.lock();
      try {
        try { findField(ownerName, fieldName).setFloat(owner, value); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
        // System.out.println(String.format(
            // "[FIELD]  seq=%d role=%d  WRITE%s %s.%s = %f  fieldId=%d",
            // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldWriteLong(long value, Object owner, int currentSiteId,
                                       boolean isVolatile, boolean isStatic,
                                       String fieldName, String ownerName) {
    if (isInside.get()) {
      try { findField(ownerName, fieldName).setLong(owner, value); }
      catch (ReflectiveOperationException ignored) {}
      return;
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try { findField(ownerName, fieldName).setLong(owner, value); }
        catch (ReflectiveOperationException ignored) {}
        return;
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

      captureOrderLock.lock();
      try {
        try { findField(ownerName, fieldName).setLong(owner, value); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
        // // System.out.println(String.format(
        //     "[FIELD]  seq=%d role=%d  WRITE%s %s.%s = %dL  fieldId=%d",
        //     seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldWriteDouble(double value, Object owner, int currentSiteId,
                                         boolean isVolatile, boolean isStatic,
                                         String fieldName, String ownerName) {
    if (isInside.get()) {
      try { findField(ownerName, fieldName).setDouble(owner, value); }
      catch (ReflectiveOperationException ignored) {}
      return;
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try { findField(ownerName, fieldName).setDouble(owner, value); }
        catch (ReflectiveOperationException ignored) {}
        return;
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

      captureOrderLock.lock();
      try {
        try { findField(ownerName, fieldName).setDouble(owner, value); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
        // System.out.println(String.format(
            // "[FIELD]  seq=%d role=%d  WRITE%s %s.%s = %f  fieldId=%d",
            // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldWriteObj(Object value, Object owner, int currentSiteId,
                                      boolean isVolatile, boolean isStatic,
                                      String fieldName, String ownerName) {
    if (isInside.get()) {
      try { findField(ownerName, fieldName).set(owner, value); }
      catch (ReflectiveOperationException ignored) {}
      return;
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try { findField(ownerName, fieldName).set(owner, value); }
        catch (ReflectiveOperationException ignored) {}
        return;
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

      captureOrderLock.lock();
      try {
        try { findField(ownerName, fieldName).set(owner, value); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId);
        // System.out.println(String.format(
            // "[FIELD]  seq=%d role=%d  WRITE%s %s.%s = %s  fieldId=%d",
            // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName,
            // value != null ? value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value)) : "null",
            // fieldId));
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  // Reflection helper: walks the class hierarchy to find a field.
  private static java.lang.reflect.Field findField(String ownerName, String fieldName)
      throws ReflectiveOperationException {
    Class<?> cls = Class.forName(ownerName.replace('/', '.'));
    while (cls != null) {
      try {
        java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException e) {
        cls = cls.getSuperclass();
      }
    }
    throw new NoSuchFieldException(ownerName + "." + fieldName);
  }

  public static void logNondetInt(int value, int siteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetInt(value, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logNondetFloat(float value, int siteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetFloat(value, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logNondetLong(long value, int siteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetLong(value, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logNondetDouble(double value, int siteId) {
    if (isInside.get()) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetDouble(value, siteId);
    } finally {
      isInside.set(false);
    }
  }
}
