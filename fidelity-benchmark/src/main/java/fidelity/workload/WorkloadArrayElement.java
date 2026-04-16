package fidelity.workload;

/**
 * Fidelity workload: concurrent writes to a shared int array, 4 threads.
 * Each thread writes its index to every element across 1 000 rounds.
 * Uses ARRAY_WRITE events with per-element tracking (element index = objCount).
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
        System.out.println("Final: " + arr[0]);
    }
}
