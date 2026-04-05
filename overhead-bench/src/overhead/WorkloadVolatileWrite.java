package overhead;

/**
 * Overhead workload: last-write-wins race on a volatile int, 4 writer threads.
 * Models high-frequency volatile stores from competing threads.
 *
 * Threads write values 1–4 to the same volatile field — 100 000 iters each.
 * Modelled after correctness/VolatileWriteRaceTest with a heavier workload.
 */
public class WorkloadVolatileWrite {

    static final int THREADS = 4;
    static final int ITERS   = 100_000;

    static volatile int x = 0;

    public static void main(String[] args) throws InterruptedException {
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int val = t + 1;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < ITERS; i++) x = val;
            });
        }
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        System.out.println("Final x: " + x);
    }
}
