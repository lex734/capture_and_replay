package correctness;

/**
 * Last-write-wins race on a volatile int.
 * Thread 1 repeatedly writes 1; thread 2 repeatedly writes 2.
 * The final value is whichever thread executed its last store most recently.
 *
 * Modelled after JCStress memory-visibility tests.
 * Expected final value: 1 or 2.
 */
public class VolatileWriteRaceTest {

    static volatile int x = 0;

    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) x = 1;
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) x = 2;
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println("Final x: " + x);
    }
}
