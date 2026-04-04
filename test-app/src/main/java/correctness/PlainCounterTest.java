package correctness;

/**
 * Data race on a plain (non-volatile, non-atomic) int.
 * Two threads perform increment and decrement with a read-modify-write cycle
 * that is not atomic, so the final value depends on how accesses interleave.
 *
 * Modelled after JCStress APISample_01_Simple (unsafe variant without atomics).
 * Expected final value: anything in [-100, 100].
 */
public class PlainCounterTest {

    static int counter = 0;

    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) counter++;
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) counter--;
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println("Final counter: " + counter);
    }
}
