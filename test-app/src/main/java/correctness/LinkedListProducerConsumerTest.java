package correctness;

/**
 * Unprotected producer/consumer queue backed by a singly linked list.
 * One thread appends nodes while another thread removes from the head.
 * Because head/tail updates are plain reads and writes, the consumer can miss
 * nodes or observe inconsistent queue state depending on the interleaving.
 *
 * Expected outcome: consumed + remaining may be less than produced, and the
 * consumer may record empty polls while the producer is still active.
 */
public class LinkedListProducerConsumerTest {

    static final int PRODUCE_COUNT = 1_000;

    static final class Node {
        final int value;
        Node next;

        Node(int value) {
            this.value = value;
        }
    }

    static Node head;
    static Node tail;
    static int consumedCount;
    static int emptyPolls;

    static void offer(int value) {
        Node node = new Node(value);
        if (tail == null) {
            head = node;
            tail = node;
            return;
        }

        tail.next = node;
        tail = node;
    }

    static Node poll() {
        Node first = head;
        if (first == null) {
            return null;
        }

        head = first.next;
        if (head == null) {
            tail = null;
        }
        first.next = null;
        return first;
    }

    static int countRemaining() {
        int remaining = 0;
        Node current = head;
        while (current != null) {
            remaining++;
            current = current.next;
        }
        return remaining;
    }

    public static void main(String[] args) throws InterruptedException {
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= PRODUCE_COUNT; i++) {
                offer(i);
            }
        });

        Thread consumer = new Thread(() -> {
            for (int i = 0; i < PRODUCE_COUNT; i++) {
                Node node = poll();
                if (node == null) {
                    emptyPolls++;
                } else {
                    consumedCount++;
                }
            }
        });

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        int remaining = countRemaining();
        System.out.println("Produced nodes: " + PRODUCE_COUNT);
        System.out.println("Consumed nodes: " + consumedCount);
        System.out.println("Empty polls: " + emptyPolls);
        System.out.println("Remaining nodes: " + remaining);
    }
}
