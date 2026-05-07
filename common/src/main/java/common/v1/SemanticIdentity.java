package common.v1;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SemanticIdentity {
    public static final class TraceObjectRef {
        private final int traceSite;
        private final int traceCount;

        public TraceObjectRef(int traceSite, int traceCount) {
            this.traceSite = traceSite;
            this.traceCount = traceCount;
        }

        public int traceSite() {
            return traceSite;
        }

        public int traceCount() {
            return traceCount;
        }

        public long packedKey() {
            return pack(traceSite, traceCount);
        }

        public boolean isNullRef() {
            return traceSite == 0 && traceCount == 0;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TraceObjectRef)) {
                return false;
            }
            TraceObjectRef that = (TraceObjectRef) other;
            return traceSite == that.traceSite && traceCount == that.traceCount;
        }

        @Override
        public int hashCode() {
            return traceSite * 31 + traceCount;
        }

        @Override
        public String toString() {
            return traceSite + ":" + traceCount;
        }
    }

    private static final ConcurrentHashMap<Long, Binding> traceToRuntime = new ConcurrentHashMap<>();
    private static final Map<Object, Long> runtimeToTrace =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Object bindingLock = new Object();

    private SemanticIdentity() {
    }

    public static void reset() {
        traceToRuntime.clear();
        runtimeToTrace.clear();
    }

    public static TraceObjectRef objectRef(int traceSite, int traceCount) {
        return new TraceObjectRef(traceSite, traceCount);
    }

    public static String semanticShapeKey(int baseType, String domainId, FieldKey fieldKey, String runtimeClassName) {
        StringBuilder builder = new StringBuilder();
        builder.append(baseType);
        if (domainId != null && !domainId.isEmpty()) {
            builder.append('|').append(domainId);
        }
        if (fieldKey != null) {
            builder.append('|').append(fieldKey);
        }
        if (runtimeClassName != null && !runtimeClassName.isEmpty()) {
            builder.append('|').append(runtimeClassName.replace('.', '/'));
        }
        return builder.toString();
    }

    public static boolean canMatch(int traceSite, int traceCount, Object runtimeObject,
            SemanticObjectEvent expectedEvent, String domainId, long lifecyclePosition, long epoch) {
        if (runtimeObject == null) {
            return traceSite == 0 && traceCount == 0;
        }
        long traceKey = pack(traceSite, traceCount);
        String semanticShapeKey = expectedEvent == null ? "" : expectedEvent.semanticObjectShape(domainId);
        synchronized (bindingLock) {
            Binding existingBinding = traceToRuntime.get(traceKey);
            if (existingBinding != null) {
                return existingBinding.runtimeObject == runtimeObject
                        && existingBinding.accepts(expectedEvent, semanticShapeKey, lifecyclePosition, epoch);
            }

            Long existingTraceKey = runtimeToTrace.get(runtimeObject);
            if (existingTraceKey != null && existingTraceKey.longValue() != traceKey) {
                return false;
            }
            return isSemanticShapeCompatible(runtimeObject, semanticShapeKey)
                    && isRuntimeCompatible(runtimeObject, expectedEvent);
        }
    }

    public static boolean commitMatch(int traceSite, int traceCount, Object runtimeObject,
            SemanticObjectEvent expectedEvent, String domainId, long lifecyclePosition, long epoch) {
        if (runtimeObject == null) {
            return traceSite == 0 && traceCount == 0;
        }
        long traceKey = pack(traceSite, traceCount);
        String semanticShapeKey = expectedEvent == null ? "" : expectedEvent.semanticObjectShape(domainId);
        synchronized (bindingLock) {
            Binding existingBinding = traceToRuntime.get(traceKey);
            if (existingBinding != null) {
                if (existingBinding.runtimeObject != runtimeObject
                        || !existingBinding.accepts(expectedEvent, semanticShapeKey, lifecyclePosition, epoch)) {
                    return false;
                }
                existingBinding.observe(expectedEvent, semanticShapeKey, lifecyclePosition, epoch, domainId);
                return true;
            }

            Long existingTraceKey = runtimeToTrace.get(runtimeObject);
            if (existingTraceKey != null && existingTraceKey.longValue() != traceKey) {
                return false;
            }
            if (!isSemanticShapeCompatible(runtimeObject, semanticShapeKey)
                    || !isRuntimeCompatible(runtimeObject, expectedEvent)) {
                return false;
            }

            Binding binding = new Binding(runtimeObject, runtimeObject.getClass().getName(),
                    semanticShapeKey, lifecyclePosition, epoch, expectedEvent, domainId);
            traceToRuntime.put(traceKey, binding);
            runtimeToTrace.put(runtimeObject, traceKey);
            return true;
        }
    }

    public static Object resolveRuntime(int traceSite, int traceCount) {
        Binding binding = traceToRuntime.get(pack(traceSite, traceCount));
        return binding == null ? null : binding.runtimeObject;
    }

    public static void bindValueIdentity(int traceSite, int traceCount, Object runtimeObject, String semanticShapeKey) {
        if (runtimeObject == null) {
            return;
        }
        long traceKey = pack(traceSite, traceCount);
        synchronized (bindingLock) {
            Binding existingBinding = traceToRuntime.get(traceKey);
            if (existingBinding != null) {
                existingBinding.observe(null, semanticShapeKey, Long.MAX_VALUE, Long.MAX_VALUE, "");
                return;
            }
            Binding binding = new Binding(runtimeObject, runtimeObject.getClass().getName(),
                    semanticShapeKey, Long.MAX_VALUE, Long.MAX_VALUE, null, "");
            traceToRuntime.put(traceKey, binding);
            runtimeToTrace.put(runtimeObject, traceKey);
        }
    }

    public static long lookupTraceId(Object runtimeObject) {
        if (runtimeObject == null) {
            return 0L;
        }
        synchronized (bindingLock) {
            Long traceKey = runtimeToTrace.get(runtimeObject);
            return traceKey == null ? Long.MIN_VALUE : traceKey.longValue();
        }
    }

    private static boolean isSemanticShapeCompatible(Object runtimeObject, String semanticShapeKey) {
        if (runtimeObject == null) {
            return true;
        }
        if (semanticShapeKey == null || semanticShapeKey.isEmpty()) {
            return true;
        }
        return semanticShapeKey.contains(runtimeObject.getClass().getName().replace('.', '/'))
                || semanticShapeKey.contains(runtimeObject.getClass().getName());
    }

    private static boolean isRuntimeCompatible(Object runtimeObject, SemanticObjectEvent expectedEvent) {
        if (runtimeObject == null || expectedEvent == null) {
            return true;
        }
        if (expectedEvent.isThread()) {
            return runtimeObject instanceof Thread;
        }
        if (expectedEvent.isArray()) {
            return runtimeObject.getClass().isArray();
        }
        if (expectedEvent.isException()) {
            return runtimeObject instanceof Throwable;
        }
        return true;
    }

    private static long pack(int traceSite, int traceCount) {
        return ((long) traceSite << 32) | (traceCount & 0xFFFFFFFFL);
    }

    private static final class Binding {
        private final Object runtimeObject;
        private final String runtimeClassName;
        private String semanticShapeKey;
        private long firstLifecyclePosition;
        private long lastLifecyclePosition;
        private long firstEpoch;
        private long lastEpoch;
        private final Set<String> observedDomains = new HashSet<>();
        private SemanticObjectEvent.Kind kind;

        private Binding(Object runtimeObject, String runtimeClassName, String semanticShapeKey,
                long lifecyclePosition, long epoch, SemanticObjectEvent expectedEvent, String domainId) {
            this.runtimeObject = runtimeObject;
            this.runtimeClassName = runtimeClassName;
            this.semanticShapeKey = semanticShapeKey == null ? "" : semanticShapeKey;
            this.firstLifecyclePosition = lifecyclePosition;
            this.lastLifecyclePosition = lifecyclePosition;
            this.firstEpoch = epoch;
            this.lastEpoch = epoch;
            this.kind = expectedEvent == null ? null : expectedEvent.kind();
            if (domainId != null && !domainId.isEmpty()) {
                this.observedDomains.add(domainId);
            }
        }

        private boolean accepts(SemanticObjectEvent expectedEvent, String shapeKey, long lifecyclePosition, long epoch) {
            if (expectedEvent != null && kind != null && expectedEvent.kind() != kind) {
                return false;
            }
            if (shapeKey != null && !shapeKey.isEmpty()) {
                if (!semanticShapeKey.isEmpty() && !semanticShapeKey.equals(shapeKey)) {
                    return false;
                }
                if (!shapeKey.contains(runtimeClassName.replace('.', '/'))
                        && !shapeKey.contains(runtimeClassName)) {
                    return false;
                }
            }
            if (epoch != Long.MAX_VALUE && lastEpoch != Long.MAX_VALUE && epoch < lastEpoch) {
                return false;
            }
            return lifecyclePosition >= firstLifecyclePosition && lifecyclePosition >= lastLifecyclePosition;
        }

        private void observe(SemanticObjectEvent expectedEvent, String shapeKey, long lifecyclePosition, long epoch,
                String domainId) {
            if (shapeKey != null && !shapeKey.isEmpty() && semanticShapeKey.isEmpty()) {
                semanticShapeKey = shapeKey;
            }
            if (expectedEvent != null && kind == null) {
                kind = expectedEvent.kind();
            }
            if (lifecyclePosition < firstLifecyclePosition) {
                firstLifecyclePosition = lifecyclePosition;
            }
            if (lifecyclePosition > lastLifecyclePosition) {
                lastLifecyclePosition = lifecyclePosition;
            }
            if (epoch < firstEpoch) {
                firstEpoch = epoch;
            }
            if (epoch > lastEpoch) {
                lastEpoch = epoch;
            }
            if (domainId != null && !domainId.isEmpty()) {
                observedDomains.add(domainId);
            }
        }
    }
}
