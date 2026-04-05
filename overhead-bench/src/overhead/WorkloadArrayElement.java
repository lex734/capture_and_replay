package overhead;

/**
 * Overhead workload: concurrent writes to a large int array, 4 threads.
 * Models bulk array-element races at high frequency.
 *
 * Each thread writes its thread-index value to every element across 1 000 rounds.
 * Modelled after correctness/ArrayElementRaceTest with a heavier workload.
 */
public class WorkloadArrayElement {

    static final int THREADS = 4;
    static final int SIZE    = 100;
    static final int ROUNDS  = 1_000;

    static int[] arr = new int[SIZE];

    public static void main(String[] args) throws InterruptedException {
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int val = t;
            threads[t] = new Thread(() -> {
                for (int round = 0; round < ROUNDS; round++)
                    for (int i = 0; i < SIZE; i++) arr[i] = val;
            });
        }
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        System.out.println("Final array[0]: " + arr[0]);
    }
}
