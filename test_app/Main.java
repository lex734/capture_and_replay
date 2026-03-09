import java.util.Arrays;

public class Main {
    // private static final Object lock = new Object();

    // private static int count = 0;
    // private static int countB = 3;

    private static double[] array = new double[50];

    public static void main(String[] args) throws InterruptedException {

        System.out.println("[App] Starting threads...");

        Thread t1 = new Thread(() ->{
            for (int i = 0; i < 50; i++) { // make sure the i is thread local
                array[i] = i;
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                array[i] = 0;
            }
        });
// read read write write races should be detected for count and countB not i
// might need to add additional logging
        t1.start();
        t2.start();

        t1.join();
        t2.join();
        System.out.println("array: " + Arrays.toString(array));
        // System.out.println("countB: " + countB);
        System.out.println("[App] Done.");
    }

    // private static void work(String name) {
    //     System.out.println(name + " attempting to lock...");
    //     synchronized (lock) {
    //         System.out.println(name + " acquired lock!");
    //         try {
    //             Thread.sleep(100); // Hold it for a bit
    //         } catch (InterruptedException e) { }
    //         System.out.println(name + " releasing lock.");
    //     }
    // }
}
