package instr;

import common.v1.StaticPrePassResult;

public final class StaticPrePassRegistry {
    private static volatile StaticPrePassResult aggregate = StaticPrePassResult.empty();

    private StaticPrePassRegistry() {
    }

    public static synchronized void reset() {
        aggregate = StaticPrePassResult.empty();
    }

    public static synchronized void record(StaticPrePassResult result) {
        if (result == null) {
            return;
        }
        aggregate = aggregate.merge(result);
    }

    public static StaticPrePassResult snapshot() {
        return aggregate;
    }
}
