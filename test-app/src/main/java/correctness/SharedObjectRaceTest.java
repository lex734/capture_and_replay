package correctness;

/**
 * Race on a shared object reference.
 * Thread 1 publishes Holder objects with value=1; thread 2 publishes with value=2.
 * The final observed value depends on which publish happened last.
 *
 * Modelled after JCStress object publication / reference visibility tests.
 * Expected final value: 1 or 2.
 */
public class SharedObjectRaceTest {

    static class Holder {
        final int value;
        Holder(int value) { this.value = value; }
    }

    static volatile Holder shared = new Holder(0);

    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) shared = new Holder(1);
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) shared = new Holder(2);
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println("Final shared.value: " + shared.value);
    }
}
