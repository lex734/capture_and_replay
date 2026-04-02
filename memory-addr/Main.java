import java.util.LinkedList;
import java.lang.reflect.Field;
import org.openjdk.jol.vm.VM;

public class Main {

    static LinkedList<Integer> list = new LinkedList<>();

    public static void main(String[] args) throws Exception {
        System.gc();
        for (int i = 1; i <= 5; i++) {
            list.add(i);
        }

        System.out.println("[App] LinkedList address: 0x"
                + Long.toHexString(VM.current().addressOf(list)));

        // Walk the internal Node objects via reflection
        Class<?> nodeClass = null;
        for (Class<?> c : LinkedList.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals("Node")) {
                nodeClass = c;
                break;
            }
        }

        Field firstField = LinkedList.class.getDeclaredField("first");
        firstField.setAccessible(true);
        Field nextField = nodeClass.getDeclaredField("next");
        nextField.setAccessible(true);
        Field itemField = nodeClass.getDeclaredField("item");
        itemField.setAccessible(true);

        Object node = firstField.get(list);
        int index = 0;
        while (node != null) {
            Object item = itemField.get(node);
            long addr = VM.current().addressOf(node);
            System.out.printf("Node[%d] value=%-4s  address=0x%x%n", index++, item, addr);
            node = nextField.get(node);
        }
    }
}
