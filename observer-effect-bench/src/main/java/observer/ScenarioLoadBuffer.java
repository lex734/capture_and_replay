package observer;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Load Buffering (LB) litmus test.
 *
 * Each actor reads the other's variable first, then writes its own.
 * Both fields are plain (non-volatile) ints.
 *
 *   actor1:  r.r1 = x;  y = 1;
 *   actor2:  r.r2 = y;  x = 1;
 *
 * Under SC, (r1=1, r2=1) is impossible:
 *   - r1=1 means actor1's load of x saw actor2's store x=1, which (in program
 *     order) happened after actor2's load of y.  So actor2's load of y executed
 *     before actor1's store y=1 (program order).  But actor1's store y=1
 *     happens after actor1's load of x (program order) — a cycle.
 *
 * On x86 (TSO): x86 does NOT perform load-store reordering at the hardware
 * level, so (1, 1) requires the JIT to hoist the load past the store.
 * Because the load and store target *different* variables (x vs y) with no
 * apparent data dependency, the JIT is more likely to perform this reorder
 * than the store-store reorder in ScenarioMessagePass.
 *
 * With the capture agent: every plain field access is wrapped in
 * synchronization, preventing the JIT load-store reorder →
 * (1, 1) disappears (observer effect).
 */
@JCStressTest
@Outcome(id = "1, 1", expect = Expect.FORBIDDEN,
        desc = "Load-store reordering: both loads see the other's store early — SC violation")
@Outcome(id = {"0, 0", "0, 1", "1, 0"}, expect = Expect.ACCEPTABLE,
        desc = "Sequentially consistent outcome")
@State
public class ScenarioLoadBuffer {

    int x;
    int y;

    @Actor
    public void actor1(II_Result r) {
        r.r1 = x;
        y = 1;
    }

    @Actor
    public void actor2(II_Result r) {
        r.r2 = y;
        x = 1;
    }
}
