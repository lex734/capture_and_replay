package fidelity.workload;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fidelity workload: AtomicInteger counter shared by 4 threads.
 * Two threads call incrementAndGet, two call decrementAndGet — 100 000 iters each.
 * Expected final value: 0 (balanced increments and decrements).
 * This workload uses ATOMIC_RMW events and is nearly deterministic under replay.
 */
public class WorkloadAtomicCounter {

    static final int THREADS = 4;
    static final int ITERS   = 100_000;

    static AtomicInteger counter = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final boolean inc = (t % 2 == 0);
            threads[t] = new Thread(() -> {
                for (int i = 0; i < ITERS; i++) {
                    if (inc) counter.incrementAndGet();
                    else     counter.decrementAndGet();
                }
            });
        }
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        System.out.println("Final: " + counter.get());
    }
}
