package observer;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Dekker mutual-exclusion litmus test.
 *
 * Each actor raises its flag and then checks whether the other's flag is set.
 * If the other flag is clear the actor considers itself to have entered the
 * critical section (r=1); otherwise it stays out (r=0).
 *
 *   actor1:  flag0 = 1;  r.r1 = (flag1 == 0) ? 1 : 0;
 *   actor2:  flag1 = 1;  r.r2 = (flag0 == 0) ? 1 : 0;
 *
 * Under SC, (r1=1, r2=1) — both actors entering the CS — is impossible:
 *   - r1=1 means actor1 saw flag1=0 after writing flag0=1.  Therefore actor2
 *     had not yet written flag1=1 at that point.  When actor2 eventually runs
 *     flag1=1 and then reads flag0, actor1's write flag0=1 has already
 *     happened — actor2 must see flag0=1 and set r2=0.  Contradiction.
 *
 * On x86 (TSO): store buffers let both actors write their flag locally and
 * read the other's stale cache line, producing (1, 1).
 *
 * With the capture agent: field accesses are serialised → (1, 1) disappears.
 */
@JCStressTest
@Outcome(id = "1, 1", expect = Expect.FORBIDDEN,
        desc = "Both actors entered the critical section — SC violation via store buffering")
@Outcome(id = {"0, 0", "1, 0", "0, 1"}, expect = Expect.ACCEPTABLE,
        desc = "At most one actor entered the critical section — sequentially consistent")
@State
public class ScenarioDekker {

    int flag0;
    int flag1;

    @Actor
    public void actor1(II_Result r) {
        flag0 = 1;
        r.r1 = (flag1 == 0) ? 1 : 0;
    }

    @Actor
    public void actor2(II_Result r) {
        flag1 = 1;
        r.r2 = (flag0 == 0) ? 1 : 0;
    }
}
