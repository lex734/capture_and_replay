package correctness;

import java.util.LinkedList;
import java.util.NoSuchElementException;

public class LinkedListContentionTest {
    static LinkedList list = new LinkedList<Integer>();

    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                list.add(1);
            };
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    list.removeLast();
                } catch (NoSuchElementException e){
                    continue;
                }
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println("Final list: " + list);
    }

}
