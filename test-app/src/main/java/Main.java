import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    // Using AtomicInteger to ensure atomicity
    static AtomicInteger x = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[App] Starting threads with sleep...");

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                int current = x.incrementAndGet();
                System.out.println("T1 increased x to: " + current);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                int current = x.decrementAndGet();
                System.out.println("T2 decreased x to: " + current);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        System.out.println("[App] Done.");
        System.out.println("Final value of x: " + x.get());
    }
}
