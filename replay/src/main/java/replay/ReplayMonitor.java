package replay;

import common.BinarySchema;
import common.IdentityMapper;
import common.IdentityMapper.BirthId;

/**
 * Replay-side counterpart to CaptureMonitor.
 *
 * Every method mirrors CaptureMonitor's signature exactly so that the
 * SyncTransformer can swap "capture/CaptureMonitor" → "replay/ReplayMonitor"
 * without changing any bytecode instrumentation logic.
 *
 * Instead of logging to a trace file, each method resolves the same identity
 * values (roleId, birthId, siteId, fieldId, …) via IdentityMapper and then
 * blocks on ReplayCoordinator.awaitTurn until this event is next in the
 * captured total order.
 */
public class ReplayMonitor {
    private static final ThreadLocal<Boolean> isInside = ThreadLocal.withInitial(() -> false);

    // ---- Sync events (monitor enter/exit, thread lifecycle, wait/notify, park/unpark) ----

    public static void checkSync(int eventType, Object lock, int currentSiteId) {
        if (isInside.get()) return;
        if (lock == null && eventType != BinarySchema.Event.THREAD_PARK
            && eventType != BinarySchema.Event.THREAD_SLEEP
            && eventType != BinarySchema.Event.THREAD_WAKEUP
            && eventType != BinarySchema.Event.THREAD_YIELD
            && eventType != BinarySchema.Event.CLASS_INIT_BEGIN
            && eventType != BinarySchema.Event.CLASS_INIT_END) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(lock, null, currentSiteId);
            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, currentSiteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-SYNC]  seq=%d role=%d  %-24s lock=%s  site=%d",
                    // seq, roleId, getEventName(eventType),
                    // lock != null ? lock.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(lock)) : "null",
                    // currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field access events (instance & static, volatile & non-volatile) ----

    public static void checkField(int eventType, Object owner, int currentSiteId,
                                boolean isVolatile, boolean isStatic,
                                String fieldName, String ownerName) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);

            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(eventType, flags);

            String evName = (eventType == BinarySchema.Event.FIELD_READ) ? "READ" : "WRITE";

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  %-5s%s %s.%s  fieldId=%d",
                    // seq, roleId, evName,
                    // isVolatile ? "(volatile)" : "",
                    // ownerName, fieldName, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array access events ----

    public static void checkArray(int eventType, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (eventType & 0xFF) | (BinarySchema.Flags.NONE << 8);

            String evName = (eventType == BinarySchema.Event.ARRAY_READ) ? "ARRAY_READ" : "ARRAY_WRITE";

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  %-12s %s[%d]  site=%d",
                    // seq, roleId, evName,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field read events (perform the actual load atomically inside the lock) ----
    // Without this, GETFIELD/GETSTATIC can race against a write from another thread
    // that was scheduled between the READ event and the physical instruction.

    public static int checkFieldReadInt(Object owner, int currentSiteId,
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
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

            int[] result = new int[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId, () -> {
                try {
                    java.lang.reflect.Field f = findField(ownerName, fieldName);
                    Class<?> t = f.getType();
                    if      (t == int.class)     result[0] = f.getInt(owner);
                    else if (t == boolean.class) result[0] = f.getBoolean(owner) ? 1 : 0;
                    else if (t == byte.class)    result[0] = f.getByte(owner);
                    else if (t == short.class)   result[0] = f.getShort(owner);
                    else if (t == char.class)    result[0] = f.getChar(owner);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  READ%s %s.%s = %d  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static float checkFieldReadFloat(Object owner, int currentSiteId,
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
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

            float[] result = new float[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId, () -> {
                try { result[0] = findField(ownerName, fieldName).getFloat(owner); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  READ%s %s.%s = %f  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static long checkFieldReadLong(Object owner, int currentSiteId,
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
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

            long[] result = new long[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId, () -> {
                try { result[0] = findField(ownerName, fieldName).getLong(owner); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  READ%s %s.%s = %dL  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static double checkFieldReadDouble(Object owner, int currentSiteId,
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
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

            double[] result = new double[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId, () -> {
                try { result[0] = findField(ownerName, fieldName).getDouble(owner); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  READ%s %s.%s = %f  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, result[0], fieldId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkFieldReadObj(Object owner, int currentSiteId,
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
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_READ, flags);

            Object[] result = new Object[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId, () -> {
                try { result[0] = findField(ownerName, fieldName).get(owner); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  READ%s %s.%s = %s  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName,
                    // result[0] != null ? result[0].getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(result[0])) : "null",
                    // fieldId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array read events (perform the actual load atomically inside the lock) ----

    public static int checkArrayReadInt(Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) {
            if (array == null) return 0;
            Class<?> comp = array.getClass().getComponentType();
            if      (comp == int.class)     return ((int[])    array)[index];
            else if (comp == byte.class)    return ((byte[])   array)[index];
            else if (comp == boolean.class) return ((boolean[])array)[index] ? 1 : 0;
            else if (comp == short.class)   return ((short[])  array)[index];
            else if (comp == char.class)    return ((char[])   array)[index];
            return 0;
        }
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) {
                Class<?> comp = array.getClass().getComponentType();
                if      (comp == int.class)     return ((int[])    array)[index];
                else if (comp == byte.class)    return ((byte[])   array)[index];
                else if (comp == boolean.class) return ((boolean[])array)[index] ? 1 : 0;
                else if (comp == short.class)   return ((short[])  array)[index];
                else if (comp == char.class)    return ((char[])   array)[index];
                return 0;
            }

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            int[] result = new int[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index, () -> {
                Class<?> comp = array.getClass().getComponentType();
                if      (comp == int.class)     result[0] = ((int[])    array)[index];
                else if (comp == byte.class)    result[0] = ((byte[])   array)[index];
                else if (comp == boolean.class) result[0] = ((boolean[])array)[index] ? 1 : 0;
                else if (comp == short.class)   result[0] = ((short[])  array)[index];
                else if (comp == char.class)    result[0] = ((char[])   array)[index];
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ %s[%d] = %d  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, result[0], currentSiteId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static float checkArrayReadFloat(Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return array != null ? ((float[]) array)[index] : 0f;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return ((float[]) array)[index];

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            float[] result = new float[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index,
                () -> result[0] = ((float[]) array)[index]);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ %s[%d] = %f  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, result[0], currentSiteId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static long checkArrayReadLong(Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return array != null ? ((long[]) array)[index] : 0L;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return ((long[]) array)[index];

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            long[] result = new long[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index,
                () -> result[0] = ((long[]) array)[index]);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ %s[%d] = %dL  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, result[0], currentSiteId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static double checkArrayReadDouble(Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return array != null ? ((double[]) array)[index] : 0.0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return ((double[]) array)[index];

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            double[] result = new double[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index,
                () -> result[0] = ((double[]) array)[index]);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ %s[%d] = %f  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, result[0], currentSiteId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    public static Object checkArrayReadObj(Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return array != null ? ((Object[]) array)[index] : null;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return ((Object[]) array)[index];

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            Object[] result = new Object[1];
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index,
                () -> result[0] = ((Object[]) array)[index]);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ %s[%d] = %s  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index,
                    // result[0] != null ? result[0].getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(result[0])) : "null",
                    // currentSiteId));
            return result[0];
        } finally {
            isInside.set(false);
        }
    }

    /**
     * Coordinates the total-order turn for an AALOAD without returning the element.
     * The caller executes the real AALOAD bytecode afterward so the JVM preserves
     * the concrete element type on the operand stack (avoids VerifyError when the
     * element type is narrower than Object, e.g. Thread[]).
     */
    public static void checkArrayCoordinate(Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_READ & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index, () -> {});
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_READ(coord) %s[%d]  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Field write events (perform the actual store atomically inside the lock) ----

    public static void checkFieldWriteInt(int value, Object owner, int currentSiteId,
                                          boolean isVolatile, boolean isStatic,
                                          String fieldName, String ownerName) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId, () -> {
                try {
                    java.lang.reflect.Field f = findField(ownerName, fieldName);
                    Class<?> t = f.getType();
                    if      (t == int.class)     f.setInt(owner, value);
                    else if (t == boolean.class) f.setBoolean(owner, value != 0);
                    else if (t == byte.class)    f.setByte(owner, (byte) value);
                    else if (t == short.class)   f.setShort(owner, (short) value);
                    else if (t == char.class)    f.setChar(owner, (char) value);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  WRITE%s %s.%s = %d  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkFieldWriteFloat(float value, Object owner, int currentSiteId,
                                            boolean isVolatile, boolean isStatic,
                                            String fieldName, String ownerName) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId, () -> {
                try {
                    findField(ownerName, fieldName).setFloat(owner, value);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  WRITE%s %s.%s = %f  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkFieldWriteLong(long value, Object owner, int currentSiteId,
                                           boolean isVolatile, boolean isStatic,
                                           String fieldName, String ownerName) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId, () -> {
                try {
                    findField(ownerName, fieldName).setLong(owner, value);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  WRITE%s %s.%s = %dL  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkFieldWriteDouble(double value, Object owner, int currentSiteId,
                                             boolean isVolatile, boolean isStatic,
                                             String fieldName, String ownerName) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId, () -> {
                try {
                    findField(ownerName, fieldName).setDouble(owner, value);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  WRITE%s %s.%s = %f  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName, value, fieldId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkFieldWriteObj(Object value, Object owner, int currentSiteId,
                                          boolean isVolatile, boolean isStatic,
                                          String fieldName, String ownerName) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(owner, ownerName, currentSiteId);
            int fieldId = IdentityMapper.getFieldId(birthId, fieldName, ownerName);
            int flags = (isVolatile ? BinarySchema.Flags.IS_VOLATILE : 0) |
                        (isStatic  ? BinarySchema.Flags.IS_STATIC   : 0);
            int packedType = BinarySchema.packType(BinarySchema.Event.FIELD_WRITE, flags);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, fieldId, () -> {
                try {
                    findField(ownerName, fieldName).set(owner, value);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-FIELD] seq=%d role=%d  WRITE%s %s.%s = %s  fieldId=%d",
                    // seq, roleId, isVolatile ? "(volatile)" : "", ownerName, fieldName,
                    // value != null ? value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value)) : "null",
                    // fieldId));
        } finally {
            isInside.set(false);
        }
    }

    // ---- Array write events (perform the actual store atomically inside the lock) ----

    public static void checkArrayWriteInt(int value, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_WRITE & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index, () -> {
                Class<?> comp = array.getClass().getComponentType();
                if      (comp == int.class)     ((int[])    array)[index] = value;
                else if (comp == byte.class)    ((byte[])   array)[index] = (byte)  value;
                else if (comp == boolean.class) ((boolean[])array)[index] = (value != 0);
                else if (comp == short.class)   ((short[])  array)[index] = (short) value;
                else if (comp == char.class)    ((char[])   array)[index] = (char)  value;
            });
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_WRITE %s[%d] = %d  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, value, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkArrayWriteFloat(float value, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_WRITE & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index,
                () -> ((float[]) array)[index] = value);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_WRITE %s[%d] = %f  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, value, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkArrayWriteLong(long value, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_WRITE & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index,
                () -> ((long[]) array)[index] = value);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_WRITE %s[%d] = %dL  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, value, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void checkArrayWriteDouble(double value, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_WRITE & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index,
                () -> ((double[]) array)[index] = value);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_WRITE %s[%d] = %f  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index, value, currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    public static void checkArrayWriteObj(Object value, Object array, int index, int currentSiteId) {
        if (isInside.get() || array == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(array, null, currentSiteId);
            int packedType = (BinarySchema.Event.ARRAY_WRITE & 0xFF) | (BinarySchema.Flags.NONE << 8);

            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, index,
                () -> ((Object[]) array)[index] = value);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-ARRAY] seq=%d role=%d  ARRAY_WRITE %s[%d] = %s  site=%d",
                    // seq, roleId,
                    // array.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(array)),
                    // index,
                    // value != null ? value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value)) : "null",
                    // currentSiteId));
        } finally {
            isInside.set(false);
        }
    }

    // Reflection helper: finds a field by walking the class hierarchy.
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

    // ---- Atomic operations ----
    //
    // Non-CAS atomics (READ, WRITE, RMW): execute natively within controlLock.
    // Total order guarantees that by the time a thread's turn arrives all prior
    // atomic writes have already committed, so the native result is identical to
    // the captured one — no value injection needed.
    //
    // CAS (ATOMIC_CAS): the boolean/witness return drives control flow, and
    // object-identity differences between runs can cause a reference CAS to fail
    // spuriously.  We therefore inject the captured result and force-set the
    // post-op cell state, mirroring the existing RMW write-back mechanism.

    /**
     * Waits for this thread's turn in the total order and acquires controlLock,
     * keeping it held so the caller's native atomic call runs atomically with the
     * ordering step.  Must be paired with {@link #endAtomicReplay()}.
     */
    public static void beginAtomicReplay(Object receiver, int index, int eventType, int currentSiteId) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);
            if (roleId == -1) return;

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            boolean isArray = (index >= 0);
            int packedType, objSite, objCount;
            if (isArray) {
                packedType = (eventType & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) | ((receiverBirth.siteId & 0xFFFF) << 16);
                objSite = receiverBirth.count;
                objCount = index;
            } else {
                packedType = BinarySchema.packType(eventType, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            ReplayCoordinator.beginAtomicReplay(roleId, packedType, objSite, objCount);
            // controlLock is now held — endAtomicReplay() will advance + release
        } finally {
            isInside.set(false); // clear before returning so native call proceeds normally
        }
    }

    /**
     * Advances the total-order index and releases controlLock.
     * Must be called once after every successful beginAtomicReplay() call.
     */
    public static void endAtomicReplay() {
        ReplayCoordinator.endAtomicReplay();
    }

    // ---- CAS injection ----
    // CAS operations need result injection: the captured boolean/witness is returned
    // and the post-op cell state is force-set so subsequent reads see the correct value.

    public static int checkAtomicCasInt(Object receiver, int index, int currentSiteId) {
        if (isInside.get()) return 0;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            final boolean isArray = (index >= 0);
            int packedType, objSite, objCount;
            if (isArray) {
                packedType = (BinarySchema.Event.ATOMIC_CAS & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) | ((receiverBirth.siteId & 0xFFFF) << 16);
                objSite = receiverBirth.count;
                objCount = index;
            } else {
                packedType = BinarySchema.packType(BinarySchema.Event.ATOMIC_CAS, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            // Force-set the post-op cell value within controlLock so no racing write
            // can interleave between the ordering step and the state update.
            java.util.function.IntConsumer writeBack = postOpValue -> {
                if (isArray)
                    ((java.util.concurrent.atomic.AtomicIntegerArray) receiver).set(index, postOpValue);
                else
                    ((java.util.concurrent.atomic.AtomicInteger) receiver).set(postOpValue);
            };
            int val = ReplayCoordinator.awaitTurnInt(roleId, packedType, objSite, objCount, writeBack);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[CHECK-ATOMIC] seq=%d role=%d  ATOMIC_CAS     %s[%d] = %d  (inject)",
                    // seq, roleId,
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // index, val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static long checkAtomicCasLong(Object receiver, int index, int currentSiteId) {
        if (isInside.get()) return 0L;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            final boolean isArray = (index >= 0);
            int packedType, objSite, objCount;
            if (isArray) {
                packedType = (BinarySchema.Event.ATOMIC_CAS & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) | ((receiverBirth.siteId & 0xFFFF) << 16);
                objSite = receiverBirth.count;
                objCount = index;
            } else {
                packedType = BinarySchema.packType(BinarySchema.Event.ATOMIC_CAS, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            // Force-set the post-op cell value (data3/data4) within controlLock,
            // then return the captured return value (data1/data2).
            final boolean isArrayFinal = isArray;
            java.util.function.LongConsumer writeBack = postOpValue -> {
                if (isArrayFinal)
                    ((java.util.concurrent.atomic.AtomicLongArray) receiver).set(index, postOpValue);
                else
                    ((java.util.concurrent.atomic.AtomicLong) receiver).set(postOpValue);
            };
            long val = ReplayCoordinator.awaitTurnLong(roleId, packedType, objSite, objCount, writeBack);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[CHECK-ATOMIC] seq=%d role=%d  ATOMIC_CAS     %s[%d] = %dL  (inject)",
                    // seq, roleId,
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // index, val));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    public static Object checkAtomicCasObj(Object receiver, int index, int currentSiteId) {
        if (isInside.get()) return null;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, currentSiteId);

            BirthId receiverBirth = IdentityMapper.getBirthId(receiver, null, currentSiteId);
            final boolean isArray = (index >= 0);
            int packedType, objSite, objCount;
            if (isArray) {
                packedType = (BinarySchema.Event.ATOMIC_CAS & 0xFF) | (BinarySchema.Flags.IS_ARRAY_ATOMIC << 8) | ((receiverBirth.siteId & 0xFFFF) << 16);
                objSite = receiverBirth.count;
                objCount = index;
            } else {
                packedType = BinarySchema.packType(BinarySchema.Event.ATOMIC_CAS, BinarySchema.Flags.NONE);
                objSite = receiverBirth.siteId;
                objCount = receiverBirth.count;
            }
            // Force-set post-op cell value within controlLock, return captured witness.
            java.util.function.Consumer<Object> writeBack = postOpValue -> {
                if (isArray)
                    ((java.util.concurrent.atomic.AtomicReferenceArray<Object>) receiver).set(index, postOpValue);
                else
                    ((java.util.concurrent.atomic.AtomicReference<Object>) receiver).set(postOpValue);
            };
            Object val = ReplayCoordinator.awaitTurnObj(roleId, packedType, objSite, objCount, writeBack);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format("[CHECK-ATOMIC] seq=%d role=%d  ATOMIC_CAS     %s[%d] = %s  (inject)",
                    // seq, roleId,
                    // receiver != null ? receiver.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(receiver)) : "null",
                    // index,
                    // val != null ? val.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(val)) : "null"));
            return val;
        } finally {
            isInside.set(false);
        }
    }

    public static void checkException(Object exception, int siteId) {
        if (isInside.get()) return;
        if (exception == null) return;
        isInside.set(true);
        try {
            long tid = Thread.currentThread().getId();
            int roleId = IdentityMapper.getRoleIdBySite(tid, siteId);
            if (roleId == -1) return;

            BirthId birthId = IdentityMapper.getBirthId(exception, null, siteId);
            int packedType = BinarySchema.packType(BinarySchema.Event.EXCEPTION_THROW, BinarySchema.Flags.NONE);
            ReplayCoordinator.awaitTurn(roleId, packedType, birthId.siteId, birthId.count, siteId);
            long seq = ReplayCoordinator.getLastMatchedSeq();
            // System.out.println(String.format(
                    // "[CHECK-THROW] seq=%d role=%d  %s  site=%d",
                    // seq, roleId,
                    // exception.getClass().getName(), siteId));
        } finally {
            isInside.set(false);
        }
    }

    public static void preRegisterThread(Thread thread) {
        if (isInside.get()) return;
        isInside.set(true);
        try {
            // Assign this thread's roleId now, before it runs a single instruction.
            // Uses the next expected role from the trace at the current idx.
            long tid = thread.getId();
            int nextRole = ReplayCoordinator.peekNextPendingRole();
            if (nextRole == -1) return;

            IdentityMapper.preAssignRole(tid, nextRole);
            ReplayCoordinator.checkIn(nextRole);
            // System.out.println("[PreRegister] role=" + nextRole);
        } finally {
            isInside.set(false);
        }
    }

    private static String getEventName(int eventType) {
        switch (eventType) {
            case BinarySchema.Event.MONITOR_ENTER:        return "MONITOR_ENTER";
            case BinarySchema.Event.MONITOR_EXIT:         return "MONITOR_EXIT";
            case BinarySchema.Event.THREAD_PARK:          return "THREAD_PARK";
            case BinarySchema.Event.THREAD_UNPARK:        return "THREAD_UNPARK";
            case BinarySchema.Event.THREAD_START:         return "THREAD_START";
            case BinarySchema.Event.THREAD_JOIN:          return "THREAD_JOIN";
            case BinarySchema.Event.THREAD_INTERRUPT:     return "THREAD_INTERRUPT";
            case BinarySchema.Event.THREAD_SLEEP:         return "THREAD_SLEEP";
            case BinarySchema.Event.THREAD_WAKEUP:        return "THREAD_WAKEUP";
            case BinarySchema.Event.THREAD_YIELD:         return "THREAD_YIELD";
            case BinarySchema.Event.THREAD_WAIT:          return "THREAD_WAIT";
            case BinarySchema.Event.THREAD_NOTIFY:        return "THREAD_NOTIFY";
            case BinarySchema.Event.THREAD_NOTIFY_ALL:    return "THREAD_NOTIFY_ALL";
            case BinarySchema.Event.THREAD_JOIN_TIMEOUT:  return "THREAD_JOIN_TIMEOUT";
            case BinarySchema.Event.THREAD_INTERRUPT_CHECK: return "THREAD_INTERRUPT_CHECK";
            case BinarySchema.Event.ATOMIC_READ:          return "ATOMIC_READ";
            case BinarySchema.Event.ATOMIC_WRITE:         return "ATOMIC_WRITE";
            case BinarySchema.Event.ATOMIC_RMW:           return "ATOMIC_RMW";
            case BinarySchema.Event.ATOMIC_CAS:           return "ATOMIC_CAS";
            case BinarySchema.Event.CLASS_INIT_BEGIN:     return "CLASS_INIT_BEGIN";
            case BinarySchema.Event.CLASS_INIT_END:       return "CLASS_INIT_END";
            case BinarySchema.Event.EXCEPTION_THROW:      return "EXCEPTION_THROW";
            default:                                      return "UNKNOWN(" + eventType + ")";
        }
    }
}
