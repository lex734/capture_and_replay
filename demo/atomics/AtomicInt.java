import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;

public class Main {
 
    static AtomicInteger x = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[App] Starting threads...");


        Thread t1 = new Thread(() ->{
            for (int i = 0; i < 50; i++) {
                x.incrementAndGet();
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                x.decrementAndGet();
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
