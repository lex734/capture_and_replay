package overhead;

/**
 * Overhead workload: plain (non-volatile) int counter shared by 4 threads.
 * Models high-frequency read-modify-write races on a shared primitive.
 *
 * Two threads increment, two threads decrement — 100 000 iterations each.
 * Modelled after correctness/PlainCounterTest with a heavier workload.
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
        System.out.println("Final counter: " + counter);
    }
}
