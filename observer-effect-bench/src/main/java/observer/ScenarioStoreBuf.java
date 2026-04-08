package observer;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Store Buffering (SB) litmus test.
 *
 * Initial state: x = 0, y = 0 (plain int fields, no synchronization)
 *
 *   actor1:  x = 1;  r.r1 = y;
 *   actor2:  y = 1;  r.r2 = x;
 *
 * Under sequential consistency (SC), (r1=0, r2=0) is impossible:
 *   - r1=0 means actor1 read y before actor2 wrote y=1, so actor2's write of
 *     y is later than actor1's read.  Program order then requires actor2's
 *     write of y happens after actor2's read of x.  But actor1's write of
 *     x=1 happens (in program order) before actor1's read of y, so actor2's
 *     read of x must see x=1.  This creates a cycle — SC-impossible.
 *
 * On x86 (TSO): store buffers allow both stores to sit in each core's local
 * buffer while the loads read stale cache lines, producing (0, 0).
 *
 * With the capture agent: every plain field access is wrapped in
 * synchronization, flushing the store buffer before each load → (0, 0)
 * disappears (observer effect).
 */
@JCStressTest
@Outcome(id = "0, 0", expect = Expect.FORBIDDEN,
        desc = "Store buffering: both reads see stale values — SC violation")
@Outcome(id = {"0, 1", "1, 0", "1, 1"}, expect = Expect.ACCEPTABLE,
        desc = "Sequentially consistent outcome")
@State
public class ScenarioStoreBuf {

    int x;
    int y;

    @Actor
    public void actor1(II_Result r) {
        x = 1;
        r.r1 = y;
    }

    @Actor
    public void actor2(II_Result r) {
        y = 1;
        r.r2 = x;
    }
}
