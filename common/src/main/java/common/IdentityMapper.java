package common;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class IdentityMapper {
    public static final int IGNORED_ROLE_ID = -1;
    public static final int GLOBAL_ROLE_ID = 0;
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
    // Keyed by "siteId:roleId" so each role gets its own allocation counter per
    // site. This makes birth IDs stable across runs: role R's N-th allocation at
    // site S always receives the same (siteId, count) regardless of what other
    // roles are doing concurrently. The count is encoded as (roleId << 16) | n
    // so that the (siteId, count) pair remains globally unique without changing
    // the trace format.
    private static final ConcurrentHashMap<String, AtomicInteger> siteCounters = new ConcurrentHashMap<>();

    // Pool string literals (from LDC): keyed by string content so every reference
    // to the same literal resolves to the same BirthId.PoolString across runs.
    private static final ConcurrentHashMap<String, BirthId.PoolString> poolStringToId = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> ignoredSites = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Long, Integer> preAssignedRoles = new ConcurrentHashMap<>();
    private static volatile Set<Integer> allowedReplayRoles = null;


    public abstract static class BirthId {
        public final int siteId;
        public final int count;

        private BirthId(int siteId, int count) {
            this.siteId = siteId;
            this.count = count;
        }

        /** Heap-allocated object: identified by allocation site + per-site ordinal. */
        public static final class Heap extends BirthId {
            /** The role that allocated this object, recorded at allocation time. */
            public final int creatorRole;

            public Heap(int siteId, int creatorRole, int count) {
                super(siteId, count);
                this.creatorRole = creatorRole;
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof Heap)) return false;
                Heap other = (Heap) o;
                return siteId == other.siteId && count == other.count;
            }

            @Override
            public int hashCode() { return siteId * 31 + count; }
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
        public static final BirthId GLOBAL = new Heap(0, 0, 0);
    }

    /**
     * Maps a thread ID to a logical Role ID.
     */
    public static int getRoleIdBySite(long tid, int siteId) {
        if (isIgnoredSite(siteId)) {
            return IGNORED_ROLE_ID;
        }
        Thread current = Thread.currentThread();
        if (shouldSkipThread(current)) {
            return IGNORED_ROLE_ID; // sentinel: caller should skip logging for this thread
        }
        Integer preAssigned = preAssignedRoles.get(tid);
        if (preAssigned != null) return preAssigned;

        Integer existingRole = tidToRoleId.get(tid);
        if (existingRole != null) return existingRole;

        Set<Integer> replayRoles = allowedReplayRoles;
        if (replayRoles != null) {
            return IGNORED_ROLE_ID;
        }

        return tidToRoleId.computeIfAbsent(tid, k -> roleCounter.getAndIncrement());
    }

    public static boolean isIgnoredRole(int roleId) {
        return roleId == IGNORED_ROLE_ID;
    }

    public static boolean shouldTraceCurrentThread(int siteId) {
        return !isIgnoredRole(getRoleIdBySite(Thread.currentThread().getId(), siteId));
    }

    public static boolean shouldSkipThread(Thread thread) {
        String name = thread.getName();
        return name.startsWith("Finalizer")
                || name.startsWith("Reference Handler")
                || name.startsWith("Signal Dispatcher")
                || name.startsWith("Notification Thread")
                || name.startsWith("Common-Cleaner")
                || name.startsWith("ForkJoinPool.commonPool")
                || name.startsWith("ForkJoinPool-")
                || name.equals("MonitorTimer")
                || name.endsWith(" monitor")
                || thread.isDaemon() && thread.getThreadGroup() != null
                        && "system".equals(thread.getThreadGroup().getName());
    }

    public static void registerIgnoredSite(int siteId) {
        if (siteId != 0) ignoredSites.put(siteId, Boolean.TRUE);
    }

    public static boolean isIgnoredSite(int siteId) {
        return ignoredSites.containsKey(siteId);
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

            // Fallback: object was not registered at allocation time (e.g. came from
            // uninstrumented code). Use the same per-role-per-site scheme so the count
            // is stable across capture and replay.
            int roleId = getRoleId(Thread.currentThread().getId());
            if (roleId < 0) roleId = 0;
            String counterKey = currentInstructionSiteId + ":" + roleId;
            AtomicInteger counter = siteCounters.computeIfAbsent(counterKey, k -> new AtomicInteger(1));
            int perRoleCount = counter.getAndIncrement();
            int encodedCount = (roleId << 16) | (perRoleCount & 0xFFFF);
            BirthId.Heap newId = new BirthId.Heap(currentInstructionSiteId, roleId, encodedCount);
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
        int roleId = getRoleId(Thread.currentThread().getId());
        if (roleId < 0) roleId = 0;
        String counterKey = siteId + ":" + roleId;
        synchronized (objToId) {
            if (objToId.containsKey(obj)) return; // already registered
            AtomicInteger counter = siteCounters.computeIfAbsent(counterKey, k -> new AtomicInteger(1));
            int perRoleCount = counter.getAndIncrement();
            // Encode roleId in the upper 16 bits so the (siteId, count) pair is
            // globally unique even when multiple roles allocate at the same site.
            int encodedCount = (roleId << 16) | (perRoleCount & 0xFFFF);
            BirthId.Heap id = new BirthId.Heap(siteId, roleId, encodedCount);
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

    public static Object resolveByBirthId(int valueSiteId, int valueCount) {
        int creatorRole = (valueCount >>> 16) & 0xFFFF;
        return idToObj.get(new BirthId.Heap(valueSiteId, creatorRole, valueCount));
    }

    /**
     * Pure reverse lookup: returns the BirthId already registered for {@code obj},
     * or {@code null} if the object is unknown (was never registered).
     * Unlike {@link #getBirthId(Object, String, int)}, this method never allocates
     * a new BirthId — it is safe to call on the replay hot path.
     */
    public static BirthId lookupBirthId(Object obj) {
        if (obj == null) return BirthId.GLOBAL;
        if (obj instanceof String) {
            String str = (String) obj;
            if (str.intern() == str) return poolStringToId.get(str);
        }
        synchronized (objToId) {
            return objToId.get(obj);
        }
    }

    /**
     * Registers a live replay object under a birth ID taken directly from the trace.
     * Used when the trace references an object (e.g. System.out) that was never
     * passed through registerAllocation or getBirthId during this replay run.
     */
    public static void registerByBirthId(int siteId, int count, Object obj) {
        if (obj == null) return;
        int creatorRole = (count >>> 16) & 0xFFFF;
        BirthId id = new BirthId.Heap(siteId, creatorRole, count);
        synchronized (objToId) {
            idToObj.putIfAbsent(id, obj);
            // Use put (not putIfAbsent): if this object was already registered under its
            // natural allocation BirthId, overwrite it with the trace BirthId so that
            // subsequent getBirthId(obj) calls (e.g. when obj is used as a field owner)
            // return the identity the trace expects, not the replay's allocation ordinal.
            objToId.put(obj, id);
        }
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

    public static void setAllowedReplayRoles(Set<Integer> roleIds) {
        if (roleIds == null) {
            allowedReplayRoles = null;
            return;
        }
        allowedReplayRoles = Collections.unmodifiableSet(new HashSet<>(roleIds));
    }


    public static void reset() {
        tidToRoleId.clear();
        roleCounter.set(1);
        preAssignedRoles.clear();
        allowedReplayRoles = null;
        staticFieldToId.clear();
        instanceFieldToId.clear();
        fieldCounter.set(1);
        objToId.clear();
        idToObj.clear();
        poolStringToId.clear();
        siteCounters.clear();
        ignoredSites.clear();
        System.out.println("[IdentityMapper] All maps cleared for new trace.");
    }
}
