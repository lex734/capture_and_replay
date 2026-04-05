package correctness;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sanity check: AtomicInteger increment/decrement from two threads always
 * sums to zero, regardless of interleaving.
 *
 * Modelled after JCStress APISample_01_Simple (safe variant with atomics).
 * Expected final value: 0
 */
public class AtomicCounterTest {

    static AtomicInteger counter = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) counter.incrementAndGet();
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) counter.decrementAndGet();
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println("Final counter: " + counter.get());
    }
}
