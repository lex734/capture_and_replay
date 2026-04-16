package fidelity.workload;

/**
 * Fidelity workload: plain (non-volatile) int counter shared by 4 threads.
 * Two threads increment, two decrement — 100 000 iters each.
 * This workload has a data race on the counter field (FIELD_READ + FIELD_WRITE).
 * Replay injects values but RMW-like races make natural agreement unpredictable.
 */
public class WorkloadPlainCounter {

    static final int THREADS = 4;
    static final int ITERS   = 100_000;

    static int counter = 0;

    public static void main(String[] args) throws InterruptedException {
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int delta = (t % 2 == 0) ? 1 : -1;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < ITERS; i++) counter += delta;
            });
        }
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        System.out.println("Final: " + counter);
    }
}
