package common;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class IdentityMapper {
    // --- Thread Roles ---
    private static final ConcurrentHashMap<Long, Integer> tidToRoleId = new ConcurrentHashMap<>();
    private static final AtomicInteger roleCounter = new AtomicInteger(1);

    public static int getRoleIdBySite(long tid, int siteId) {
        // A thread is defined by the first Site ID it hits
        Integer existingRole = tidToRoleId.get(tid);
        if (existingRole != null) return existingRole;

        synchronized (tidToRoleId) {
            if (tidToRoleId.containsKey(tid)) return tidToRoleId.get(tid);

            int roleId = roleCounter.getAndIncrement();
            tidToRoleId.put(tid, roleId);

            return roleId;
        }
    }

    // --- Object Identities ---
    // We map the SITE that discovered the object to a Logical ID.
    // This assumes that the same code location consistently interacts 
    // with the same logical object across runs.
    // Given that multiple objects might be initialised at the same site
    // We identify it by its sequence of initialisation using siteCounter
    private static final Map<Object, BirthId> objToId =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<Integer, AtomicInteger> siteCounters = new ConcurrentHashMap<>();

    public static class BirthId {
        public final int siteId;
        public final int count;
        public BirthId(int s, int c) {this.siteId = s; this.count = c;}
    }

    public static BirthId getBirthId(Object obj, int siteId) {
        if (obj == null) return new BirthId(0, 0);

        synchronized (objToId) {
            BirthId existing = objToId.get(obj);
            if (existing != null) return existing;

            AtomicInteger counter = siteCounters.computeIfAbsent(siteId, k -> new AtomicInteger(1));
            BirthId newId = new BirthId(siteId, counter.getAndIncrement());
            objToId.put(obj, newId);
            return newId;
        }
    }

    // field identity relative to the object
    private static final ConcurrentHashMap<String, Integer> fieldToFirstSite = new ConcurrentHashMap<>();

    public static int getFieldId(BirthId objectBirthId, String fieldName, int currentSiteId) {
        String fieldKey = objectBirthId.siteId + ":" + objectBirthId.count + ":" + fieldName;
        return fieldToFirstSite.computeIfAbsent(fieldKey, k -> currentSiteId);
    }

    public static void reset() {
        tidToRoleId.clear();
        roleCounter.set(1);
        objToId.clear();
        siteCounters.clear();
        fieldToFirstSite.clear();
        System.out.println("[IdentityMapper] Maps reset for new run.");
    }
}
