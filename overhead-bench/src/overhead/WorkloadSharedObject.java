package overhead;

/**
 * Overhead workload: repeated volatile publication of heap-allocated objects, 4 threads.
 * Models mixed object allocation and volatile reference races.
 *
 * Each thread allocates a new Holder and writes it to a shared volatile field.
 * Modelled after correctness/SharedObjectRaceTest with a heavier workload.
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
        System.out.println("Final shared.value: " + shared.value);
    }
}
