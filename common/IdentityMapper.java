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
    private static final AtomicInteger siteIdCounter = new AtomicInteger(1);
    private static final AtomicInteger fieldCounter = new AtomicInteger(1);

    // --- Mappings ---
    private static final ConcurrentHashMap<Long, Integer> tidToRoleId = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> siteStringToId = new ConcurrentHashMap<>();
    
    // Maps "ClassName#fieldName" to a unique global ID for static variables
    private static final ConcurrentHashMap<String, Integer> staticFieldToId = new ConcurrentHashMap<>();
    
    // Maps "ClassID:fieldName" to a unique ID for instance variables
    private static final ConcurrentHashMap<String, Integer> instanceFieldToId = new ConcurrentHashMap<>();

    // Identity tracking for actual objects on the heap
    private static final Map<Object, BirthId> objToId = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<Integer, AtomicInteger> siteCounters = new ConcurrentHashMap<>();

    public static class BirthId {
        public final int siteId;
        public final int count;
        public BirthId(int s, int c) { this.siteId = s; this.count = c; }
        
        // Static constant representing the "Global/Static" birthplace
        public static final BirthId GLOBAL = new BirthId(0, 0);
    }

    /**
     * Maps a thread ID to a logical Role ID.
     */
    public static int getRoleIdBySite(long tid, int siteId) {
        return tidToRoleId.computeIfAbsent(tid, k -> roleCounter.getAndIncrement());
    }

    /**
     * Maps a code location string to a unique Site ID.
     */
    public static int getSiteId(String siteString) {
        return siteStringToId.computeIfAbsent(siteString, k -> siteIdCounter.getAndIncrement());
    }

    /**
     * Returns a BirthId for objects. 
     * If the object is null (Static), it returns the GLOBAL BirthId (Site 0).
     */
    public static BirthId getBirthId(Object obj, String ownerName, int currentInstructionSiteId) {
        if (obj == null) {
            // Static fields belong to the "Global Site" (0), not the current thread's instruction site.
            return BirthId.GLOBAL;
        }

        synchronized (objToId) {
            BirthId existing = objToId.get(obj);
            if (existing != null) return existing;

            AtomicInteger counter = siteCounters.computeIfAbsent(currentInstructionSiteId, k -> new AtomicInteger(1));
            BirthId newId = new BirthId(currentInstructionSiteId, counter.getAndIncrement());
            objToId.put(obj, newId);
            return newId;
        }
    }

    /**
     * Returns a unique ID for a field.
     * If birthId is GLOBAL (Site 0), it generates a static field ID based on the class name.
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

    public static void reset() {
        tidToRoleId.clear();
        roleCounter.set(1);
        siteStringToId.clear();
        siteIdCounter.set(1);
        staticFieldToId.clear();
        instanceFieldToId.clear();
        fieldCounter.set(1);
        objToId.clear();
        siteCounters.clear();
        System.out.println("[IdentityMapper] All maps cleared for new trace.");
    }
}
