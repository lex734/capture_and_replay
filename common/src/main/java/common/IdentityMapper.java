package common;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class IdentityMapper {
    public static final int IGNORED_ROLE_ID = -1;
    public static final int GLOBAL_ROLE_ID = 0;

    private static final AtomicInteger roleCounter = new AtomicInteger(1);
    private static final AtomicInteger fieldCounter = new AtomicInteger(1);

    private static final ConcurrentHashMap<Long, Integer> tidToRoleId = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> staticFieldToId = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> instanceFieldToId = new ConcurrentHashMap<>();

    // Capture-only object identity tables.
    private static final Map<Object, BirthId> objToId = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<BirthId, Object> idToObj = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<String, BirthId> poolStringToId = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> ignoredSites = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> preAssignedRoles = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Long, Object> traceToReplayObject = new ConcurrentHashMap<>();
    private static final Map<Object, Long> replayObjectToTraceId = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Object replayBindingLock = new Object();

    public abstract static class BirthId {
        public final int siteId;
        public final int count;

        private BirthId(int siteId, int count) {
            this.siteId = siteId;
            this.count = count;
        }

        public static final class Heap extends BirthId {
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
            public int hashCode() {
                return siteId * 31 + count;
            }
        }

        public static final class PoolString extends BirthId {
            public PoolString(int contentSiteId) {
                super(contentSiteId, 0);
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof PoolString)) return false;
                return siteId == ((PoolString) o).siteId;
            }

            @Override
            public int hashCode() {
                return siteId;
            }
        }

        public static final BirthId GLOBAL = new Heap(0, 0, 0);
    }

    public static int getRoleIdBySite(long tid, int siteId) {
        if (isIgnoredSite(siteId)) {
            return IGNORED_ROLE_ID;
        }
        Thread current = Thread.currentThread();
        if (shouldSkipThread(current)) {
            return IGNORED_ROLE_ID;
        }
        Integer preAssigned = preAssignedRoles.get(tid);
        int roleId = tidToRoleId.computeIfAbsent(tid, k -> roleCounter.getAndIncrement());
        return preAssigned != null ? preAssigned : roleId;
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

    private static boolean usesTraceObjectIds() {
        return !"REPLAY".equals(System.getProperty("tool.mode"));
    }

    private static BirthId newTraceBirthId() {
        long value = TraceObjectId.next().value();
        if ((value & 0xFFFF000000000000L) != 0L) {
            throw new IllegalStateException("Trace object ID exceeds 48-bit transitional encoding budget: " + value);
        }
        return new BirthId.Heap((int) (value >>> 32), 0, (int) value);
    }

    private static long packBirthId(BirthId id) {
        return ((long) id.siteId << 32) | (id.count & 0xFFFFFFFFL);
    }

    private static BirthId traceBirthId(int siteId, int count) {
        return new BirthId.Heap(siteId, 0, count);
    }

    private static void bindTraceToRuntime(BirthId traceId, Object runtimeObject) {
        long traceKey = packBirthId(traceId);
        synchronized (replayBindingLock) {
            Object existingObject = traceToReplayObject.get(traceKey);
            if (existingObject != null && existingObject != runtimeObject) {
                throw new IllegalStateException("Conflicting replay binding for trace object " + traceKey);
            }
            Long existingTrace = replayObjectToTraceId.get(runtimeObject);
            if (existingTrace != null && existingTrace.longValue() != traceKey) {
                throw new IllegalStateException("Runtime object already bound to different trace object");
            }
            if (runtimeObject != null) {
                traceToReplayObject.putIfAbsent(traceKey, runtimeObject);
                replayObjectToTraceId.putIfAbsent(runtimeObject, traceKey);
            }
        }
    }

    public static boolean bindOrCheckTraceObject(int traceSite, int traceCount, Object runtimeObject) {
        if (runtimeObject == null) {
            return traceSite == BirthId.GLOBAL.siteId && traceCount == BirthId.GLOBAL.count;
        }
        BirthId trace = traceBirthId(traceSite, traceCount);
        long traceKey = packBirthId(trace);
        synchronized (replayBindingLock) {
            Object existingObject = traceToReplayObject.get(traceKey);
            if (existingObject != null && existingObject != runtimeObject) {
                return false;
            }
            Long existingTrace = replayObjectToTraceId.get(runtimeObject);
            if (existingTrace != null && existingTrace.longValue() != traceKey) {
                return false;
            }
            traceToReplayObject.putIfAbsent(traceKey, runtimeObject);
            replayObjectToTraceId.putIfAbsent(runtimeObject, traceKey);
        }
        return true;
    }

    public static boolean isBoundTraceIdentity(int traceSite, int traceCount) {
        return traceToReplayObject.containsKey(packBirthId(traceBirthId(traceSite, traceCount)));
    }

    public static long lookupTraceIdForObject(Object obj) {
        if (obj == null) {
            return packBirthId(BirthId.GLOBAL);
        }
        synchronized (replayBindingLock) {
            Long traceId = replayObjectToTraceId.get(obj);
            return traceId != null ? traceId.longValue() : Long.MIN_VALUE;
        }
    }

    public static BirthId getBirthId(Object obj, String ownerName, int currentInstructionSiteId) {
        if (!usesTraceObjectIds()) {
            throw new IllegalStateException("Replay must not request synthetic birth IDs");
        }
        if (obj == null) {
            return BirthId.GLOBAL;
        }

        if (obj instanceof String) {
            String str = (String) obj;
            if (str.intern() == str) {
                return poolStringToId.computeIfAbsent(str, k -> {
                    BirthId id = newTraceBirthId();
                    idToObj.put(id, str);
                    return id;
                });
            }
        }

        synchronized (objToId) {
            BirthId existing = objToId.get(obj);
            if (existing != null) {
                return existing;
            }
            BirthId id = newTraceBirthId();
            objToId.put(obj, id);
            idToObj.put(id, obj);
            return id;
        }
    }

    public static int getFieldId(BirthId birthId, String fieldName, String ownerClassName) {
        if (birthId.siteId == 0) {
            String key = ownerClassName + "#" + fieldName;
            return staticFieldToId.computeIfAbsent(key, k -> fieldCounter.getAndIncrement());
        } else {
            String key = birthId.siteId + ":" + fieldName;
            return instanceFieldToId.computeIfAbsent(key, k -> fieldCounter.getAndIncrement());
        }
    }

    public static void registerAllocation(Object obj, int siteId) {
        if (!usesTraceObjectIds()) return;
        if (obj == null) return;
        synchronized (objToId) {
            if (objToId.containsKey(obj)) return;
            BirthId id = newTraceBirthId();
            objToId.put(obj, id);
            idToObj.put(id, obj);
        }
    }

    public static void registerPoolString(String str, int contentSiteId) {
        if (!usesTraceObjectIds()) return;
        if (str == null) return;
        poolStringToId.computeIfAbsent(str, k -> {
            BirthId id = newTraceBirthId();
            idToObj.put(id, str);
            return id;
        });
    }

    public static Object resolveByBirthId(int valueSiteId, int valueCount) {
        if (usesTraceObjectIds()) {
            return idToObj.get(traceBirthId(valueSiteId, valueCount));
        }
        return traceToReplayObject.get(packBirthId(traceBirthId(valueSiteId, valueCount)));
    }

    public static void registerByBirthId(int siteId, int count, Object obj) {
        if (obj == null) return;
        BirthId traceId = traceBirthId(siteId, count);
        bindTraceToRuntime(traceId, obj);
    }

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
        traceToReplayObject.clear();
        replayObjectToTraceId.clear();
        ignoredSites.clear();
        System.out.println("[IdentityMapper] All maps cleared for new trace.");
    }
}
