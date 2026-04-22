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

  private static boolean shouldIgnoreCurrentRole(long siteId) {
    return !IdentityMapper.shouldTraceCurrentThread(siteId);
  }

  public static void logSync(int eventType, Object lock, long siteId) {
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

  public static void logThreadStart(Object thread, long siteId) {
    if (!(thread instanceof Thread)) return;
    logSync(BinarySchema.Event.THREAD_START, thread, siteId);
  }

  public static int vectorSize(java.util.Vector<?> vector, long siteId) {
    if (isInside.get()) return vector.size();
    if (shouldIgnoreCurrentRole(siteId)) return vector.size();
    isInside.set(true);
    captureOrderLock.lock();
    try {
      int result = vector.size();
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, vector, siteId);
      return result;
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  public static Object vectorElementAt(java.util.Vector<?> vector, int index, long siteId) {
    if (isInside.get()) return vector.elementAt(index);
    if (shouldIgnoreCurrentRole(siteId)) return vector.elementAt(index);
    isInside.set(true);
    captureOrderLock.lock();
    try {
      Object result = vector.elementAt(index);
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, vector, siteId);
      return result;
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  public static void vectorRemoveAllElements(java.util.Vector<?> vector, long siteId) {
    if (isInside.get()) {
      vector.removeAllElements();
      return;
    }
    if (shouldIgnoreCurrentRole(siteId)) {
      vector.removeAllElements();
      return;
    }
    isInside.set(true);
    captureOrderLock.lock();
    try {
      vector.removeAllElements();
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, vector, siteId);
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  public static java.util.Set<?> mapKeySet(java.util.Map<?, ?> map, long siteId) {
    if (isInside.get()) return map.keySet();
    if (shouldIgnoreCurrentRole(siteId)) return map.keySet();
    isInside.set(true);
    captureOrderLock.lock();
    try {
      java.util.Set<?> result = map.keySet();
      IdentityMapper.registerAllocation(result, siteId);
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, map, siteId);
      return result;
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  public static java.util.Set<?> mapEntrySet(java.util.Map<?, ?> map, long siteId) {
    if (isInside.get()) return map.entrySet();
    if (shouldIgnoreCurrentRole(siteId)) return map.entrySet();
    isInside.set(true);
    captureOrderLock.lock();
    try {
      java.util.Set<?> result = map.entrySet();
      IdentityMapper.registerAllocation(result, siteId);
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, map, siteId);
      return result;
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object mapPut(java.util.Map map, Object key, Object value, long siteId) {
    if (isInside.get()) return map.put(key, value);
    if (shouldIgnoreCurrentRole(siteId)) return map.put(key, value);
    isInside.set(true);
    captureOrderLock.lock();
    try {
      Object result = map.put(key, value);
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, map, siteId);
      return result;
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object mapRemove(java.util.Map map, Object key, long siteId) {
    if (isInside.get()) return map.remove(key);
    if (shouldIgnoreCurrentRole(siteId)) return map.remove(key);
    isInside.set(true);
    captureOrderLock.lock();
    try {
      Object result = map.remove(key);
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, map, siteId);
      return result;
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  public static void mapClear(java.util.Map<?, ?> map, long siteId) {
    if (isInside.get()) {
      map.clear();
      return;
    }
    if (shouldIgnoreCurrentRole(siteId)) {
      map.clear();
      return;
    }
    isInside.set(true);
    captureOrderLock.lock();
    try {
      map.clear();
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, map, siteId);
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  public static java.util.Iterator<?> setIterator(java.util.Set<?> set, long siteId) {
    if (isInside.get()) return set.iterator();
    if (shouldIgnoreCurrentRole(siteId)) return set.iterator();
    isInside.set(true);
    captureOrderLock.lock();
    try {
      java.util.Iterator<?> result = set.iterator();
      IdentityMapper.registerAllocation(result, siteId);
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, set, siteId);
      return result;
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  public static boolean iteratorHasNext(java.util.Iterator<?> iterator, long siteId) {
    if (isInside.get()) return iterator.hasNext();
    if (shouldIgnoreCurrentRole(siteId)) return iterator.hasNext();
    isInside.set(true);
    captureOrderLock.lock();
    try {
      boolean result = iterator.hasNext();
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, iterator, siteId);
      return result;
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  public static Object iteratorNext(java.util.Iterator<?> iterator, long siteId) {
    if (isInside.get()) return iterator.next();
    if (shouldIgnoreCurrentRole(siteId)) return iterator.next();
    isInside.set(true);
    captureOrderLock.lock();
    try {
      TraceLogger.logSync(BinarySchema.Event.COLLECTION_OP, iterator, siteId);
      return iterator.next();
    } finally {
      captureOrderLock.unlock();
      isInside.set(false);
    }
  }

  public static void logField(int eventType, Object owner, long siteId, boolean isVolatile, boolean isStatic, String fieldName, String ownerName) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logField(eventType, owner, siteId, isVolatile, isStatic, fieldName, ownerName);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicInt(int intValue, int postOpValue, Object receiver, int index, int eventType, long siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicInt(intValue, postOpValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicLong(long longValue, long postOpValue, Object receiver, int index, int eventType, long siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicLong(longValue, postOpValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logAtomicObj(Object objValue, Object postOpValue, Object receiver, int index, int eventType, long siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logAtomicObj(objValue, postOpValue, receiver, index, eventType, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logException(Object exception, long siteId) {
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

  public static void logArray(int eventType, Object array, int index, long siteId) {
    if (isInside.get() || array == null) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
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

  public static Condition captureNewCondition(Lock lock, long siteId) {
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

  public static void endAtomicCaptureInt(int returnValue, Object receiver, int index, int eventType, long siteId) {
    try {
      if (shouldIgnoreCurrentRole(siteId)) return;
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

  public static void endAtomicCaptureLong(long returnValue, Object receiver, int index, int eventType, long siteId) {
    try {
      if (shouldIgnoreCurrentRole(siteId)) return;
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

  public static void endAtomicCaptureObj(Object returnValue, Object receiver, int index, int eventType, long siteId) {
    try {
      if (shouldIgnoreCurrentRole(siteId)) return;
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

  public static int logFieldReadInt(Object owner, long currentSiteId,
                                    boolean isVolatile, boolean isStatic,
                                    String fieldName, String ownerName) {
    if (isInside.get()) {
      try { return readIntField(findField(ownerName, fieldName), owner); }
      catch (ReflectiveOperationException e) { return 0; }
    }
    isInside.set(true);
    try {
      long tid = Thread.currentThread().getId();
      int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
      if (roleId == -1) {
        try { return readIntField(findField(ownerName, fieldName), owner); }
        catch (ReflectiveOperationException e) { return 0; }
      }
      BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
      if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
        return readRawIntField(ownerName, fieldName, owner);
      }
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
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId, 0, 0, 0, IdentityMapper.getCreatorRoleId(birthId), 0);
      } finally {
        captureOrderLock.unlock();
      }
      return result[0];
    } finally {
      isInside.set(false);
    }
  }

  public static float logFieldReadFloat(Object owner, long currentSiteId,
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
      if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
        return readRawFloatField(ownerName, fieldName, owner);
      }
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
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId, 0, 0, 0, IdentityMapper.getCreatorRoleId(birthId), 0);
      } finally {
        captureOrderLock.unlock();
      }
      return result[0];
    } finally {
      isInside.set(false);
    }
  }

  public static long logFieldReadLong(Object owner, long currentSiteId,
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
      if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
        return readRawLongField(ownerName, fieldName, owner);
      }
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
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId, 0, 0, 0, IdentityMapper.getCreatorRoleId(birthId), 0);
      } finally {
        captureOrderLock.unlock();
      }
      return result[0];
    } finally {
      isInside.set(false);
    }
  }

  public static double logFieldReadDouble(Object owner, long currentSiteId,
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
      if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
        return readRawDoubleField(ownerName, fieldName, owner);
      }
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
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId, 0, 0, 0, IdentityMapper.getCreatorRoleId(birthId), 0);
      } finally {
        captureOrderLock.unlock();
      }
      return result[0];
    } finally {
      isInside.set(false);
    }
  }

  public static Object logFieldReadObj(Object owner, long currentSiteId,
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
      if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
        return readRawObjField(ownerName, fieldName, owner);
      }
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
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId, 0, 0, 0, IdentityMapper.getCreatorRoleId(birthId), 0);
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

  public static void logFieldWriteInt(int value, Object owner, long currentSiteId,
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
      if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
        writeRawIntField(value, ownerName, fieldName, owner);
        return;
      }
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
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId, 0, 0, 0, IdentityMapper.getCreatorRoleId(birthId), 0);
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldWriteFloat(float value, Object owner, long currentSiteId,
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
      if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
        writeRawFloatField(value, ownerName, fieldName, owner);
        return;
      }
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

      captureOrderLock.lock();
      try {
        try { findField(ownerName, fieldName).setFloat(owner, value); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId, 0, 0, 0, IdentityMapper.getCreatorRoleId(birthId), 0);
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldWriteLong(long value, Object owner, long currentSiteId,
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
      if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
        writeRawLongField(value, ownerName, fieldName, owner);
        return;
      }
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

      captureOrderLock.lock();
      try {
        try { findField(ownerName, fieldName).setLong(owner, value); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId, 0, 0, 0, IdentityMapper.getCreatorRoleId(birthId), 0);
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldWriteDouble(double value, Object owner, long currentSiteId,
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
      if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
        writeRawDoubleField(value, ownerName, fieldName, owner);
        return;
      }
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

      captureOrderLock.lock();
      try {
        try { findField(ownerName, fieldName).setDouble(owner, value); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId, 0, 0, 0, IdentityMapper.getCreatorRoleId(birthId), 0);
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  public static void logFieldWriteObj(Object value, Object owner, long currentSiteId,
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
      if (IdentityMapper.shouldSkipObjectEvent(birthId, isStatic)) {
        writeRawObjField(value, ownerName, fieldName, owner);
        return;
      }
      int fieldId    = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
      int flags      = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0)
                     | (isStatic   ? BinarySchema.Flags.IS_STATIC   : 0);
      int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

      captureOrderLock.lock();
      try {
        try { findField(ownerName, fieldName).set(owner, value); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        long seq = TraceLogger.nextSeq();
        BinarySchema.write(seq, (long) roleId, packedType, birthId.siteId, birthId.count, fieldId, 0, 0, 0, IdentityMapper.getCreatorRoleId(birthId), 0);
      } finally {
        captureOrderLock.unlock();
      }
    } finally {
      isInside.set(false);
    }
  }

  // Reflection helper: walks the class hierarchy to find a field.
  private static int readIntField(java.lang.reflect.Field f, Object owner)
      throws ReflectiveOperationException {
    Class<?> t = f.getType();
    if (t == boolean.class) return f.getBoolean(owner) ? 1 : 0;
    if (t == byte.class)    return f.getByte(owner);
    if (t == short.class)   return f.getShort(owner);
    if (t == char.class)    return f.getChar(owner);
    return f.getInt(owner);
  }

  private static int readRawIntField(String ownerName, String fieldName, Object owner) {
    try { return readIntField(findField(ownerName, fieldName), owner); }
    catch (ReflectiveOperationException e) { return 0; }
  }

  private static float readRawFloatField(String ownerName, String fieldName, Object owner) {
    try { return findField(ownerName, fieldName).getFloat(owner); }
    catch (ReflectiveOperationException e) { return 0f; }
  }

  private static long readRawLongField(String ownerName, String fieldName, Object owner) {
    try { return findField(ownerName, fieldName).getLong(owner); }
    catch (ReflectiveOperationException e) { return 0L; }
  }

  private static double readRawDoubleField(String ownerName, String fieldName, Object owner) {
    try { return findField(ownerName, fieldName).getDouble(owner); }
    catch (ReflectiveOperationException e) { return 0.0; }
  }

  private static Object readRawObjField(String ownerName, String fieldName, Object owner) {
    try { return findField(ownerName, fieldName).get(owner); }
    catch (ReflectiveOperationException e) { return null; }
  }

  private static void writeRawIntField(int value, String ownerName, String fieldName, Object owner) {
    try {
      java.lang.reflect.Field f = findField(ownerName, fieldName);
      Class<?> t = f.getType();
      if      (t == int.class)     f.setInt(owner, value);
      else if (t == boolean.class) f.setBoolean(owner, value != 0);
      else if (t == byte.class)    f.setByte(owner, (byte) value);
      else if (t == short.class)   f.setShort(owner, (short) value);
      else if (t == char.class)    f.setChar(owner, (char) value);
    } catch (ReflectiveOperationException ignored) {}
  }

  private static void writeRawFloatField(float value, String ownerName, String fieldName, Object owner) {
    try { findField(ownerName, fieldName).setFloat(owner, value); }
    catch (ReflectiveOperationException ignored) {}
  }

  private static void writeRawLongField(long value, String ownerName, String fieldName, Object owner) {
    try { findField(ownerName, fieldName).setLong(owner, value); }
    catch (ReflectiveOperationException ignored) {}
  }

  private static void writeRawDoubleField(double value, String ownerName, String fieldName, Object owner) {
    try { findField(ownerName, fieldName).setDouble(owner, value); }
    catch (ReflectiveOperationException ignored) {}
  }

  private static void writeRawObjField(Object value, String ownerName, String fieldName, Object owner) {
    try { findField(ownerName, fieldName).set(owner, value); }
    catch (ReflectiveOperationException ignored) {}
  }

  private static java.lang.reflect.Field findField(String ownerName, String fieldName)
      throws ReflectiveOperationException {
    Class<?> cls = findOwnerClass(ownerName);
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

  private static Class<?> findOwnerClass(String ownerName) throws ClassNotFoundException {
    String className = ownerName.replace('/', '.');
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) cl = ClassLoader.getSystemClassLoader();
    try {
      return Class.forName(className, false, cl);
    } catch (ClassNotFoundException ignored) {
      try {
        return Class.forName(className, false, ClassLoader.getSystemClassLoader());
      } catch (ClassNotFoundException ignoredAgain) {
        Class<?> stackClass = java.lang.StackWalker
            .getInstance(java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE)
            .walk(frames -> frames
                .map(java.lang.StackWalker.StackFrame::getDeclaringClass)
                .filter(cls -> cls.getName().equals(className))
                .findFirst()
                .orElse(null));
        if (stackClass != null) return stackClass;
        throw new ClassNotFoundException(className);
      }
    }
  }

  public static void logNondetInt(int value, long siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetInt(value, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logNondetFloat(float value, long siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetFloat(value, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logNondetLong(long value, long siteId) {
    if (isInside.get()) return;
    if (shouldIgnoreCurrentRole(siteId)) return;
    isInside.set(true);
    try {
      TraceLogger.logNondetLong(value, siteId);
    } finally {
      isInside.set(false);
    }
  }

  public static void logNondetDouble(double value, long siteId) {
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
