package common.v1;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class TraceObjectId {
    private static final AtomicLong COUNTER = new AtomicLong(1);

    private final long value;

    public TraceObjectId(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("trace object IDs must be positive");
        }
        this.value = value;
    }

    public long value() {
        return value;
    }

    public static TraceObjectId next() {
        return new TraceObjectId(COUNTER.getAndIncrement());
    }

    public static void resetSequence() {
        COUNTER.set(1);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TraceObjectId)) {
            return false;
        }
        return value == ((TraceObjectId) other).value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
