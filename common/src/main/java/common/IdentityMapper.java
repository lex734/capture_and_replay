package common;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Uniquely identify objects that are on the heap across runs
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

    // Identity tracking for heap-allocated objects.
    // Pool strings are tracked separately in poolStringToId and never enter objToId,
    // which avoids WeakHashMap's equals()-based key lookup conflating a pool string
    // with a heap string of the same content.
    private static final Map<Object, BirthId> objToId = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<BirthId, Object> idToObj = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<Integer, AtomicInteger> siteCounters = new ConcurrentHashMap<>();
    // Per-role-per-site counters: key = (roleId << 32) | siteId.
    // Used by registerAllocation so that each role's Nth object at a given site
    // always gets the same birth count across capture and replay runs,
    // independent of how other roles interleave their allocations.
    private static final ConcurrentHashMap<Long, AtomicInteger> roleSiteCounters = new ConcurrentHashMap<>();

    // Pool string literals (from LDC): keyed by string content so every reference
    // to the same literal resolves to the same BirthId.PoolString across runs.
    private static final ConcurrentHashMap<String, BirthId.PoolString> poolStringToId = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Long, Integer> preAssignedRoles = new ConcurrentHashMap<>();


    public abstract static class BirthId {
        public final int siteId;
        public final int count;

        private BirthId(int siteId, int count) {
            this.siteId = siteId;
            this.count = count;
        }

        /**
         * Heap-allocated object: identified by (creatorRoleId, siteId, roleLocalCount).
         * creatorRoleId is the role that executed the NEW instruction, -1 for
         * infrastructure/unregistered threads that fall back to global counters.
         * Including creatorRoleId prevents idToObj collisions when two roles each
         * allocate their first object at the same site (both would get count=1 under
         * per-role-per-site counting, but their BirthIds are still distinct).
         */
        public static final class Heap extends BirthId {
            public final int creatorRoleId;
            public Heap(int creatorRoleId, int siteId, int count) {
                super(siteId, count);
                this.creatorRoleId = creatorRoleId;
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof Heap)) return false;
                Heap other = (Heap) o;
                return creatorRoleId == other.creatorRoleId
                    && siteId == other.siteId
                    && count == other.count;
            }

            @Override
            public int hashCode() { return (creatorRoleId * 31 + siteId) * 31 + count; }
        }

        /**
         * String pool (interned) literal: identified by content-derived siteId only.
         * count is always 0 — there is exactly one canonical object per string content.
         * equals() checks instanceof PoolString so a Heap(siteId, 0) and a
         * PoolString(siteId) never collide in idToObj even when siteId matches.
         */
        public static final class PoolString extends BirthId {
            public PoolString(int contentSiteId) { super(contentSiteId, 0); }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof PoolString)) return false;
                return siteId == ((PoolString) o).siteId;
            }

            @Override
            public int hashCode() { return siteId; }
        }

        // Static constant representing the "Global/Static" birthplace
        public static final BirthId GLOBAL = new Heap(-1, 0, 0);
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
     *
     * For String objects the intern-check MUST happen before objToId is consulted.
     * WeakHashMap uses equals() for key lookup, so a pool string stored in objToId
     * would match any heap string with identical content, collapsing two distinct
     * objects onto the same BirthId. By routing pool strings exclusively to
     * poolStringToId we keep the two spaces completely separate.
     */
    public static BirthId getBirthId(Object obj, String ownerName, int currentInstructionSiteId) {
        if (obj == null) {
            return BirthId.GLOBAL;
        }

        if (obj instanceof String) {
            String str = (String) obj;
            if (str.intern() == str) {
                // Pool (interned) string: route to poolStringToId, never objToId.
                return poolStringToId.computeIfAbsent(str, k -> {
                    BirthId.PoolString id = new BirthId.PoolString(currentInstructionSiteId);
                    idToObj.put(id, str);
                    return id;
                });
            }
        }

        synchronized (objToId) {
            BirthId existing = objToId.get(obj);
            if (existing != null)
                return existing;

            // Object was never passed through registerAllocation — lazy-register it.
            // Use per-role-per-site counters for the same reason as registerAllocation.
            long tid = Thread.currentThread().getId();
            int roleId = getRoleIdBySite(tid, currentInstructionSiteId);
            AtomicInteger counter;
            if (roleId >= 0) {
                long key = ((long) roleId << 32) | (currentInstructionSiteId & 0xFFFFFFFFL);
                counter = roleSiteCounters.computeIfAbsent(key, k -> new AtomicInteger(1));
            } else {
                counter = siteCounters.computeIfAbsent(currentInstructionSiteId, k -> new AtomicInteger(1));
            }
            BirthId.Heap newId = new BirthId.Heap(roleId, currentInstructionSiteId, counter.getAndIncrement());
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
     * Assigns a stable BirthId.Heap based on the allocation site and allocation
     * order at that site. This ensures the same object gets the same BirthId in
     * both capture and replay runs, regardless of access order.
     */
    public static void registerAllocation(Object obj, int siteId) {
        if (obj == null) return;
        long tid = Thread.currentThread().getId();
        int roleId = getRoleIdBySite(tid, siteId);
        synchronized (objToId) {
            if (objToId.containsKey(obj)) return; // already registered
            AtomicInteger counter;
            if (roleId >= 0) {
                // Per-role-per-site counter: role=2's Nth object at site S always
                // gets count=N, independent of allocations by other roles.
                long key = ((long) roleId << 32) | (siteId & 0xFFFFFFFFL);
                counter = roleSiteCounters.computeIfAbsent(key, k -> new AtomicInteger(1));
            } else {
                // Infrastructure / unregistered thread — fall back to global counter.
                counter = siteCounters.computeIfAbsent(siteId, k -> new AtomicInteger(1));
            }
            BirthId.Heap id = new BirthId.Heap(roleId, siteId, counter.getAndIncrement());
            objToId.put(obj, id);
            idToObj.put(id, obj);
        }
    }

    /**
     * Called for string LDC instructions (pool/interned strings).
     * Pool strings are stored in a dedicated map keyed by string content so that
     * every reference to the same literal — regardless of which class or thread
     * loads it first — resolves to the same BirthId.PoolString across runs.
     * They are deliberately kept out of objToId so that a heap String with
     * identical content (e.g. new String("foo")) gets its own distinct BirthId
     * through the normal registerAllocation path.
     */
    public static void registerPoolString(String str, int contentSiteId) {
        if (str == null) return;
        poolStringToId.computeIfAbsent(str, k -> {
            BirthId.PoolString id = new BirthId.PoolString(contentSiteId);
            idToObj.put(id, str);
            return id;
        });
    }

    public static Object resolveByBirthId(int creatorRoleId, int valueSiteId, int valueCount) {
        return idToObj.get(new BirthId.Heap(creatorRoleId, valueSiteId, valueCount));
    }

    /** Looks up the roleId for a thread that has already been assigned one, or -1. */
    public static int getRoleId(long tid) {
        Integer preAssigned = preAssignedRoles.get(tid);
        if (preAssigned != null) return preAssigned;
        Integer roleId = tidToRoleId.get(tid);
        return roleId != null ? roleId : -1;
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
        poolStringToId.clear();
        siteCounters.clear();
        roleSiteCounters.clear();
        // System.out.println("[IdentityMapper] All maps cleared for new trace.");
    }
}
