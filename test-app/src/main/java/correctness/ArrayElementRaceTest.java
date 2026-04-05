package correctness;

import java.util.Arrays;

/**
 * Race on plain (non-atomic) array elements.
 * Thread 1 writes its index value into every cell; thread 2 zeros every cell.
 * The final state of each element depends on which thread's store was last.
 *
 * Modelled after JCStress array-element visibility tests.
 * Expected final array: some mix of 0s and their index values.
 */
public class ArrayElementRaceTest {

    static final int SIZE = 10;
    static int[] arr = new int[SIZE];

    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int round = 0; round < 20; round++)
                for (int i = 0; i < SIZE; i++) arr[i] = i;
        });
        Thread t2 = new Thread(() -> {
            for (int round = 0; round < 20; round++)
                for (int i = 0; i < SIZE; i++) arr[i] = 0;
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println("Final array: " + Arrays.toString(arr));
    }
}
