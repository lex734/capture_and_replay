package common;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Uniquely identify objects that are on the heap across runs
public class IdentityMapper {
    /**
     * Execution role semantics:
     *   roleId > 0  : managed application/concurrency role, written to trace records.
     *   roleId == 0 : global/static object coordinate only; never used as a live thread role.
     *   roleId == -1: ignored execution context, never written as a trace role.
     *
     * BirthId.GLOBAL also uses creatorRoleId=-1 to identify static/global state.
     * That is an object-coordinate sentinel, not a replayable execution role.
     */
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
    // IdentityHashMap uses == / System.identityHashCode() for key lookup, so it never
    // calls the object's own hashCode() — this avoids NPEs when getBirthId is called
    // on a partially-constructed object whose hashCode() reads an uninitialised field.
    // Pool strings are tracked separately in poolStringToId: the same string content
    // must map to the same BirthId across capture and replay runs (where the interned
    // object reference may differ), which requires content-keyed lookup, not identity.
    private static final Map<Object, BirthId> objToId = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<BirthId, Object> idToObj = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<Long, AtomicInteger> siteCounters = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> ignoredSites = new ConcurrentHashMap<>();
    // Per-role-per-site counters: key = exact (roleId, siteId) pair.
    // Used by registerAllocation so that each role's Nth object at a given site
    // always gets the same birth count across capture and replay runs,
    // independent of how other roles interleave their allocations.
    private static final ConcurrentHashMap<RoleSiteKey, AtomicInteger> roleSiteCounters = new ConcurrentHashMap<>();

    // Pool string literals (from LDC): keyed by string content so every reference
    // to the same literal resolves to the same BirthId.PoolString across runs.
    private static final ConcurrentHashMap<String, BirthId.PoolString> poolStringToId = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Long, Integer> preAssignedRoles = new ConcurrentHashMap<>();

    private static final class RoleSiteKey {
        private final int roleId;
        private final long siteId;

        private RoleSiteKey(int roleId, long siteId) {
            this.roleId = roleId;
            this.siteId = siteId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RoleSiteKey)) return false;
            RoleSiteKey other = (RoleSiteKey) o;
            return roleId == other.roleId && siteId == other.siteId;
        }

        @Override
        public int hashCode() {
            long h = (roleId * 31L) + siteId;
            return (int) (h ^ (h >>> 32));
        }
    }


    public abstract static class BirthId {
        public final long siteId;
        public final int count;

        private BirthId(long siteId, int count) {
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
            public Heap(int creatorRoleId, long siteId, int count) {
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
            public int hashCode() {
                long h = (creatorRoleId * 31L + siteId) * 31L + count;
                return (int) (h ^ (h >>> 32));
            }
        }

        /**
         * String pool (interned) literal: identified by content-derived siteId only.
         * count is always 0 — there is exactly one canonical object per string content.
         * equals() checks instanceof PoolString so a Heap(siteId, 0) and a
         * PoolString(siteId) never collide in idToObj even when siteId matches.
         */
        public static final class PoolString extends BirthId {
            public PoolString(long contentSiteId) { super(contentSiteId, 0); }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof PoolString)) return false;
                return siteId == ((PoolString) o).siteId;
            }

            @Override
            public int hashCode() { return (int) (siteId ^ (siteId >>> 32)); }
        }

        // Static constant representing the "Global/Static" birthplace
        public static final BirthId GLOBAL = new Heap(GLOBAL_ROLE_ID, 0, 0);
    }

    /**
     * Maps a thread ID to a logical Role ID.
     */
    public static int getRoleIdBySite(long tid, long siteId) {
        return getRoleIdForThread(Thread.currentThread(), tid, siteId);
    }

    public static int getRoleIdForThread(Thread thread, long siteId) {
        return getRoleIdForThread(thread, thread.getId(), siteId);
    }

    private static int getRoleIdForThread(Thread thread, long tid, long siteId) {
        if (isIgnoredSite(siteId)) {
            return IGNORED_ROLE_ID;
        }
        if (shouldSkipThread(thread)) {
            return IGNORED_ROLE_ID; // sentinel: caller should skip logging for this thread
        }
        Integer preAssigned = preAssignedRoles.get(tid);
        int roleId = tidToRoleId.computeIfAbsent(tid, k -> roleCounter.getAndIncrement());
        if (preAssigned != null) return preAssigned;
        return roleId;
    }

    public static boolean isIgnoredRole(int roleId) {
        return roleId == IGNORED_ROLE_ID;
    }

    public static boolean shouldTraceCurrentThread(long siteId) {
        return !isIgnoredRole(getRoleIdBySite(Thread.currentThread().getId(), siteId));
    }

    public static boolean shouldSkipThread(Thread thread) {
        String name = thread.getName();

        // JVM / benchmark infrastructure threads — never assign roles to these,
        // they are non-deterministic across runs and should not be replayed.
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

    /**
     * Returns a BirthId for objects.
     * If the object is null (Static), it returns the GLOBAL BirthId (Site 0).
     *
     * For String objects the intern-check MUST happen before objToId is consulted.
     * Pool strings must be keyed by content (not identity) so that the same literal
     * resolves to the same BirthId across capture and replay, where the interned
     * object reference may differ. By routing pool strings exclusively to
     * poolStringToId we keep the two spaces completely separate.
     */
    public static BirthId getBirthId(Object obj, String ownerName, long currentInstructionSiteId) {
        if (obj == null) {
            return BirthId.GLOBAL;
        }

        BirthId groovyReflectionId = stableGroovyReflectionId(obj);
        if (groovyReflectionId != null) {
            synchronized (objToId) {
                objToId.put(obj, groovyReflectionId);
                idToObj.put(groovyReflectionId, obj);
            }
            return groovyReflectionId;
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
            int roleId = isIgnoredSite(currentInstructionSiteId)
                    ? IGNORED_ROLE_ID
                    : getRoleIdBySite(tid, currentInstructionSiteId);
            AtomicInteger counter;
            if (roleId >= 0) {
                RoleSiteKey key = new RoleSiteKey(roleId, currentInstructionSiteId);
                counter = roleSiteCounters.computeIfAbsent(key, k -> new AtomicInteger(1));
            } else {
                counter = siteCounters.computeIfAbsent(currentInstructionSiteId, k -> new AtomicInteger(1));
            }
            BirthId.Heap newId = new BirthId.Heap(roleId, currentInstructionSiteId, counter.getAndIncrement());
            objToId.put(obj, newId);
            idToObj.put(newId, obj);

            // Temporary debug logging for lazy registrations. Enable with -Didentity.lazylog=true
            if (Boolean.getBoolean("identity.lazylog")) {
                String threadName = Thread.currentThread().getName();
                System.err.println(String.format(
                    "[LAZY-REGISTER] tid=%d thread=%s role=%d site=%d owner=%s objClass=%s",
                    tid, threadName, roleId, currentInstructionSiteId,
                    ownerName == null ? "<null>" : ownerName, obj.getClass().getName()));
                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                // skip first 3 frames (getStackTrace, this block, caller) and print a few frames
                for (int i = 3; i < Math.min(st.length, 10); i++) {
                    System.err.println("    at " + st[i]);
                }
                System.err.flush();
            }

            return newId;
        }
    }

    /**
     * Returns a unique ID for a field.
     * If birthId is GLOBAL (Site 0), it generates a static field ID based on the
     * class name.
     */
    public static int getFieldId(BirthId birthId, String fieldName, String ownerClassName) {
        String key;
        if (birthId.siteId == 0) {
            // This is a static field. We map it by ClassName + FieldName.
            // This ensures every thread in the JVM gets the same ID for "MyClass.myVar".
            key = "S:" + ownerClassName + "#" + fieldName;
        } else {
            // This is an instance field. We map it by the Object's Birth Site + Field Name.
            key = "I:" + ownerClassName + "#" + birthId.siteId + ":" + fieldName;
        }
        int id = key.hashCode();
        return id != 0 ? id : 1;
    }

    public static int getCreatorRoleId(BirthId birthId) {
        return birthId instanceof BirthId.Heap ? ((BirthId.Heap) birthId).creatorRoleId : IGNORED_ROLE_ID;
    }

    /**
     * Non-static heap objects created outside a managed role are runtime
     * bookkeeping from the replay system's perspective. Static/global
     * coordinates use creatorRole=0 via BirthId.GLOBAL, so keep site 0 events
     * replayable.
     */
    public static boolean shouldSkipObjectEvent(BirthId birthId, boolean isStatic) {
        return !isStatic && birthId.siteId != 0 && getCreatorRoleId(birthId) == IGNORED_ROLE_ID;
    }

    public static void registerIgnoredSite(long siteId) {
        if (siteId != 0L) ignoredSites.put(siteId, Boolean.TRUE);
    }

    public static boolean isIgnoredSite(long siteId) {
        return ignoredSites.containsKey(siteId);
    }

    /**
     * Called immediately after a NEW/NEWARRAY/ANEWARRAY/MULTIANEWARRAY completes.
     * Assigns a stable BirthId.Heap based on the allocation site and allocation
     * order at that site. This ensures the same object gets the same BirthId in
     * both capture and replay runs, regardless of access order.
     */
    public static void registerAllocation(Object obj, long siteId) {
        if (obj == null) return;
        BirthId stableId = stableGroovyReflectionId(obj);
        if (stableId != null) {
            synchronized (objToId) {
                objToId.put(obj, stableId);
                idToObj.put(stableId, obj);
            }
            return;
        }
        long tid = Thread.currentThread().getId();
        int roleId = isIgnoredSite(siteId) ? IGNORED_ROLE_ID : getRoleIdBySite(tid, siteId);
        synchronized (objToId) {
            if (objToId.containsKey(obj)) return; // already registered
            AtomicInteger counter;
            if (roleId >= 0) {
                // Per-role-per-site counter: role=2's Nth object at site S always
                // gets count=N, independent of allocations by other roles.
                RoleSiteKey key = new RoleSiteKey(roleId, siteId);
                counter = roleSiteCounters.computeIfAbsent(key, k -> new AtomicInteger(1));
            } else {
                // Infrastructure / unregistered thread — fall back to global counter.
                counter = siteCounters.computeIfAbsent(siteId, k -> new AtomicInteger(1));
            }
            BirthId.Heap id = new BirthId.Heap(roleId, siteId, counter.getAndIncrement());
            objToId.put(obj, id);
            idToObj.put(id, obj);
            // Optional debug logging for eager registrations. Enable with -Didentity.alloclog=true
            if (Boolean.getBoolean("identity.alloclog")) {
                String threadName = Thread.currentThread().getName();
                System.err.println(String.format(
                    "[EAGER-REGISTER] tid=%d thread=%s role=%d site=%d objClass=%s",
                    tid, threadName, roleId, siteId, obj.getClass().getName()));
                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                for (int i = 3; i < Math.min(st.length, 10); i++) {
                    System.err.println("    at " + st[i]);
                }
                System.err.flush();
            }
        }
    }

    private static BirthId stableGroovyReflectionId(Object obj) {
        String name = obj.getClass().getName();
        String namespace = null;
        if (name.equals("org.codehaus.groovy.reflection.CachedMethod")
                || name.startsWith("org.codehaus.groovy.reflection.GeneratedMetaMethod")) {
            namespace = "groovy.CachedMethod";
        } else if (name.equals("org.codehaus.groovy.reflection.CachedField")) {
            namespace = "groovy.CachedField";
        } else if (name.equals("org.codehaus.groovy.reflection.CachedConstructor")) {
            namespace = "groovy.CachedConstructor";
        }
        return namespace == null ? null : new BirthId.Heap(IGNORED_ROLE_ID,
                stableSiteId("stable-class-wrapper:" + namespace), 0);
    }

    private static long stableSiteId(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            long id = ((long) (digest[0] & 0x7F) << 56)
                    | ((long) (digest[1] & 0xFF) << 48)
                    | ((long) (digest[2] & 0xFF) << 40)
                    | ((long) (digest[3] & 0xFF) << 32)
                    | ((long) (digest[4] & 0xFF) << 24)
                    | ((long) (digest[5] & 0xFF) << 16)
                    | ((long) (digest[6] & 0xFF) << 8)
                    | (long) (digest[7] & 0xFF);
            return id == 0L ? 1L : id;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive stable object id for " + key, e);
        }
    }

    /**
     * Groovy's reflection cache creates ClassInfo/CachedClass objects lazily as
     * runtime code asks about Java classes.  The exact cache warm-up order can
     * differ between capture and replay, so constructor-count birth ids are too
     * brittle for these wrappers.  Collapse each wrapper kind to one stable
     * identity so cache warm-up bookkeeping does not make replay diverge.
     */
    public static void registerStableClassWrapper(Object obj, Class<?> wrappedClass, String namespace) {
        if (obj == null || wrappedClass == null || namespace == null) return;
        BirthId.Heap id = new BirthId.Heap(IGNORED_ROLE_ID,
                stableSiteId("stable-class-wrapper:" + namespace), 0);
        synchronized (objToId) {
            BirthId existing = objToId.get(obj);
            if (existing != null && existing.siteId == id.siteId && existing.count == id.count
                    && getCreatorRoleId(existing) == id.creatorRoleId) {
                return;
            }
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
    public static void registerPoolString(String str, long contentSiteId) {
        if (str == null) return;
        poolStringToId.computeIfAbsent(str, k -> {
            BirthId.PoolString id = new BirthId.PoolString(contentSiteId);
            idToObj.put(id, str);
            return id;
        });
    }

    public static Object resolveByBirthId(int creatorRoleId, long valueSiteId, int valueCount) {
        return idToObj.get(new BirthId.Heap(creatorRoleId, valueSiteId, valueCount));
    }

    /** Looks up the roleId for a thread that has already been assigned one, or -1. */
    public static int getRoleId(long tid) {
        Integer preAssigned = preAssignedRoles.get(tid);
        if (preAssigned != null) return preAssigned;
        Integer roleId = tidToRoleId.get(tid);
        return roleId != null ? roleId : IGNORED_ROLE_ID;
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
        ignoredSites.clear();
        // System.out.println("[IdentityMapper] All maps cleared for new trace.");
    }
}
