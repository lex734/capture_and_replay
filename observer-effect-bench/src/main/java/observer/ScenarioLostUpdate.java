package observer;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * Lost Update litmus test.
 *
 * Two actors each increment a shared plain int counter.  An @Arbiter
 * observes the final value after both actors have finished.
 *
 *   actor1:  counter++;   // getfield → iadd → putfield
 *   actor2:  counter++;
 *   arbiter: r.r1 = counter;
 *
 * Under SC with atomic increments, r1 must be 2.
 * Without atomicity: both actors can read 0, each compute 1, and both write 1
 * → one increment is lost → r1 = 1.
 *
 * This test differs fundamentally from ScenarioStoreBuf / ScenarioDekker:
 *   - The forbidden outcome arises from a non-atomic read-modify-write, not
 *     from store buffering.  The compound operation (read, add, write) is
 *     never atomic as a single unit at the bytecode level.
 *
 * With the capture agent: each individual bytecode (getfield, putfield) is
 * wrapped in synchronization, but the three-step increment is still NOT
 * performed as an atomic unit.  Therefore r1=1 should continue to appear
 * even with the agent — demonstrating that the agent prevents memory-ordering
 * races but does NOT provide compound atomicity.
 *
 * Expected result: the observer effect is NOT detected for this scenario.
 * This serves as a negative control, showing the boundary of what the
 * capture agent's synchronization can and cannot fix.
 */
@JCStressTest
@Outcome(id = "1", expect = Expect.FORBIDDEN,
        desc = "Lost update: one increment overwritten — non-atomic read-modify-write")
@Outcome(id = "2", expect = Expect.ACCEPTABLE,
        desc = "Both increments visible — sequentially consistent")
@State
public class ScenarioLostUpdate {

    int counter;

    @Actor
    public void actor1() {
        counter++;
    }

    @Actor
    public void actor2() {
        counter++;
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = counter;
    }
}
