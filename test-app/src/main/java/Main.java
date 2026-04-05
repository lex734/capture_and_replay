import java.util.concurrent.atomic.AtomicInteger;

public class Main {
 
    static String x = "Hello, World!";

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[App] Starting threads...");


        Thread t1 = new Thread(() ->{
            for (int i = 0; i < 50; i++) {
                x = x.concat("Hi");
                System.out.println("Thread 1: " + x);
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                x = x.replace("Hi", "Hello");
                System.out.println("Thread 2: " + x);
            }
        });

        t1.start();
        t2.start();

        t1.join();
        t2.join();
        
        System.out.println("[App] Done.");
        System.out.println("Final value of x: " + x);
    }

}
