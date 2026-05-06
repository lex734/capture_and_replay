package common.v1;

import java.util.Objects;

public final class ConcurrentEntryRoot {
    private final ConcurrentRootKind kind;
    private final MethodKey methodKey;

    public ConcurrentEntryRoot(ConcurrentRootKind kind, MethodKey methodKey) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.methodKey = Objects.requireNonNull(methodKey, "methodKey");
    }

    public ConcurrentRootKind kind() {
        return kind;
    }

    public MethodKey methodKey() {
        return methodKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ConcurrentEntryRoot)) {
            return false;
        }
        ConcurrentEntryRoot that = (ConcurrentEntryRoot) other;
        return kind == that.kind && methodKey.equals(that.methodKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, methodKey);
    }

    @Override
    public String toString() {
        return kind + ":" + methodKey;
    }
}
