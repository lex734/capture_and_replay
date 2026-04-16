package fidelity.workload;

/**
 * Fidelity workload: volatile publication of heap-allocated objects, 4 threads.
 * Each thread allocates a Holder and writes it to a shared volatile reference 50 000 times.
 * Tests object-reference fidelity under FIELD_WRITE (volatile) events.
 */
public class WorkloadSharedObject {

    static final int THREADS = 4;
    static final int ITERS   = 50_000;

    static class Holder {
        final int value;
        Holder(int v) { this.value = v; }
    }

    static volatile Holder shared = new Holder(0);

    public static void main(String[] args) throws InterruptedException {
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int val = t + 1;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < ITERS; i++) shared = new Holder(val);
            });
        }
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        System.out.println("Final: " + shared.value);
    }
}
