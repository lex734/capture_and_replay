public class Main {
    public static void main(String[] args) {

        // Thread 1: The "Worker" (runs smoothly)
        Thread workerThread = new Thread(() -> {
            for (int i = 1; i <= 3; i++) {
                System.out.println("Worker: Processing task " + i);
                try { Thread.sleep(400); } catch (InterruptedException e) {}
            }
            System.out.println("Worker: All tasks complete!");
        });

        // Thread 2: The "Validator" (throws an explicit exception)
        Thread validatorThread = new Thread(() -> {
            boolean systemCriticalError = true;

            if (systemCriticalError) {
                System.out.println("Validator: Critical error detected! Throwing exception...");
                
                // Explicitly creating and throwing an exception
                throw new RuntimeException("Manual override: System safety breach!");
            }

            System.out.println("Validator: This line will never be reached.");
        });

        workerThread.start();
        validatorThread.start();
    }
}