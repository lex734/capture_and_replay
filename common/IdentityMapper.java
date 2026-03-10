package common;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class IdentityMapper {
    // --- ID Spaces ---
    // 0 is reserved for GLOBAL/STATIC scope
    private static final AtomicInteger roleCounter = new AtomicInteger(1);
    private static final AtomicInteger fieldCounter = new AtomicInteger(1);

    // --- Mappings ---
    private static final ConcurrentHashMap<Long, Integer> tidToRoleId = new ConcurrentHashMap<>();

    // Maps "ClassName#fieldName" to a unique global ID for static variables
    private static final ConcurrentHashMap<String, Integer> staticFieldToId = new ConcurrentHashMap<>();

    // Maps "ClassID:fieldName" to a unique ID for instance variables
    private static final ConcurrentHashMap<String, Integer> instanceFieldToId = new ConcurrentHashMap<>();

    // Identity tracking for actual objects on the heap.
    // objToId: weak keys (Objects) so dead objects are evicted automatically.
    // idToObj: strong keys (BirthIds are small value objects); values are Objects
    //          held only weakly so we don't prevent GC of user objects.
    private static final Map<Object, BirthId> objToId = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<BirthId, Object> idToObj = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<Integer, AtomicInteger> siteCounters = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Long, Integer> preAssignedRoles = new ConcurrentHashMap<>();

    public static class BirthId {
        public final int siteId;
        public final int count;

        public BirthId(int s, int c) {
            this.siteId = s;
            this.count = c;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BirthId)) return false;
            BirthId other = (BirthId) o;
            return siteId == other.siteId && count == other.count;
        }

        @Override
        public int hashCode() {
            return siteId * 31 + count;
        }

        // Static constant representing the "Global/Static" birthplace
        public static final BirthId GLOBAL = new BirthId(0, 0);
    }

    /**
     * Maps a thread ID to a logical Role ID.
     */
    public static int getRoleIdBySite(long tid, int siteId) {
        Thread current = Thread.currentThread();
        String name = current.getName();

        // JVM infrastructure threads — never assign roles to these,
        // they are non-deterministic across runs and should not be replayed
        if (name.startsWith("Finalizer")
                || name.startsWith("Reference Handler")
                || name.startsWith("Signal Dispatcher")
                || name.startsWith("Notification Thread")
                || name.startsWith("Common-Cleaner")
                || name.startsWith("ForkJoinPool.commonPool")
                || name.startsWith("ForkJoinPool-")
                || current.isDaemon() && current.getThreadGroup() != null
                        && "system".equals(current.getThreadGroup().getName())) {
            return -1; // sentinel: caller should skip logging for this thread
        }
        Integer preAssigned = preAssignedRoles.get(tid);
        int roleId = tidToRoleId.computeIfAbsent(tid, k -> roleCounter.getAndIncrement());
        if (preAssigned != null) return preAssigned;
        return roleId;
    }

    /**
     * Returns a BirthId for objects.
     * If the object is null (Static), it returns the GLOBAL BirthId (Site 0).
     */
    public static BirthId getBirthId(Object obj, String ownerName, int currentInstructionSiteId) {
        if (obj == null) {
            // Static fields belong to the "Global Site" (0), not the current thread's
            // instruction site.
            return BirthId.GLOBAL;
        }

        synchronized (objToId) {
            BirthId existing = objToId.get(obj);
            if (existing != null)
                return existing;

            AtomicInteger counter = siteCounters.computeIfAbsent(currentInstructionSiteId, k -> new AtomicInteger(1));
            BirthId newId = new BirthId(currentInstructionSiteId, counter.getAndIncrement());
            objToId.put(obj, newId);
            idToObj.put(newId, obj);
            return newId;
        }
    }

    /**
     * Returns a unique ID for a field.
     * If birthId is GLOBAL (Site 0), it generates a static field ID based on the
     * class name.
     */
    public static int getFieldId(BirthId birthId, String fieldName, String ownerClassName) {
        if (birthId.siteId == 0) {
            // This is a static field. We map it by ClassName + FieldName.
            // This ensures every thread in the JVM gets the same ID for "MyClass.myVar".
            String key = ownerClassName + "#" + fieldName;
            return staticFieldToId.computeIfAbsent(key, k -> fieldCounter.getAndIncrement());
        } else {
            // This is an instance field. We map it by the Object's Birth Site + Field Name.
            String key = birthId.siteId + ":" + fieldName;
            return instanceFieldToId.computeIfAbsent(key, k -> fieldCounter.getAndIncrement());
        }
    }

    /**
     * Called immediately after a NEW/NEWARRAY/ANEWARRAY/MULTIANEWARRAY completes.
     * Assigns a stable BirthId based on the allocation site and allocation order
     * at that site. This ensures the same object gets the same BirthId in both
     * capture and replay runs, regardless of access order.
     */
    public static void registerAllocation(Object obj, int siteId) {
        if (obj == null) return;
        synchronized (objToId) {
            if (objToId.containsKey(obj)) return; // already registered
            AtomicInteger counter = siteCounters.computeIfAbsent(siteId, k -> new AtomicInteger(1));
            BirthId id = new BirthId(siteId, counter.getAndIncrement());
            objToId.put(obj, id);
            idToObj.put(id, obj);
        }
    }

    public static Object resolveByBirthId(int valueSiteId, int valueCount) {
        return idToObj.get(new BirthId(valueSiteId, valueCount));
    }
    public static void preAssignRole(long tid, int roleId) {
        preAssignedRoles.put(tid, roleId);
    }


    public static void reset() {
        tidToRoleId.clear();
        roleCounter.set(1);
        preAssignedRoles.clear();
        staticFieldToId.clear();
        instanceFieldToId.clear();
        fieldCounter.set(1);
        objToId.clear();
        idToObj.clear();
        siteCounters.clear();
        System.out.println("[IdentityMapper] All maps cleared for new trace.");
    }
}
