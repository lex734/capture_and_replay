import java.util.concurrent.atomic.AtomicIntegerArray;

public class Main {
 
    static AtomicIntegerArray atomicArray = new AtomicIntegerArray(50);

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[App] Starting threads...");


        Thread t1 = new Thread(() ->{
            for (int i = 0; i < 50; i++) {
                atomicArray.set(i, i);
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                atomicArray.set(i, 0);
            }
        });

        t1.start();
        t2.start();

        t1.join();
        t2.join();
        
        System.out.println("[App] Done.");
        System.out.println("Final array values:" + atomicArray);
    }

}
