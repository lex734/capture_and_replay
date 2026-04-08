package observer;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Message-passing (MP) litmus test.
 *
 * The sender writes a payload then raises a flag; the receiver reads the flag
 * then reads the payload.  Both fields are plain (non-volatile) ints.
 *
 *   sender:    data = 42;  flag = 1;
 *   receiver:  r.r1 = flag;  r.r2 = data;
 *
 * Under SC, (r1=1, r2=0) is impossible:
 *   - r1=1 means the receiver saw flag=1, which the sender wrote after
 *     data=42 (program order).  Therefore the sender's write of data=42
 *     happened-before the receiver's read of data → receiver must see 42.
 *
 * In practice on x86 (TSO), hardware store-store reordering does not occur,
 * but the JIT compiler is free to reorder the sender's two stores (data and
 * flag are independent plain fields).  JCStress's tight stress loop triggers
 * JIT compilation paths where this reordering manifests, producing (1, 0).
 *
 * With the capture agent: both stores in the sender are wrapped in
 * synchronization (memory barriers), preventing JIT reordering →
 * (1, 0) disappears (observer effect).
 */
@JCStressTest
@Outcome(id = "1, 0", expect = Expect.FORBIDDEN,
        desc = "Flag seen but data not visible — stores reordered, impossible under SC")
@Outcome(id = {"0, 0", "0, 42", "1, 42"}, expect = Expect.ACCEPTABLE,
        desc = "Sequentially consistent outcome")
@State
public class ScenarioMessagePass {

    int data;
    int flag;

    @Actor
    public void sender() {
        data = 42;
        flag = 1;
    }

    @Actor
    public void receiver(II_Result r) {
        r.r1 = flag;
        r.r2 = data;
    }
}
