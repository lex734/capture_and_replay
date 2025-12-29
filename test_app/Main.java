public class Main {
    private static final Object lock = new Object();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[App] Starting threads...");

        Thread t1 = new Thread(() -> work("Worker-1"));
        Thread t2 = new Thread(() -> work("Worker-2"));

        t1.start();
        t2.start();

        t1.join();
        t2.join();
        
        System.out.println("[App] Done.");
    }

    private static void work(String name) {
        System.out.println(name + " attempting to lock...");
        synchronized (lock) {
            System.out.println(name + " acquired lock!");
            try {
                Thread.sleep(100); // Hold it for a bit
            } catch (InterruptedException e) { }
            System.out.println(name + " releasing lock.");
        }
    }
}
