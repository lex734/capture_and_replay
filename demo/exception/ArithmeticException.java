public class Main {
    public static void main(String[] args) {
        
        // Thread 1: The "Safe" Thread
        Thread safeThread = new Thread(() -> {
            try {
                Thread.sleep(500); // Wait a bit so we can see the chaos start
                System.out.println("Safe Thread: I'm still running fine!");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        // Thread 2: The "Chaos" Thread
        Thread crashingThread = new Thread(() -> {
            System.out.println("Crashing Thread: About to divide by zero...");
            int result = 10 / 0; // Naturally produces ArithmeticException
            System.out.println("This will never print: " + result);
        });

        safeThread.start();
        crashingThread.start();
    }
}
