/**
 * Inner class and lambda capture test.
 * Creates many anonymous inner classes, lambdas, and method references
 * that capture outer state — tests class naming and retained attribution
 * for synthetic classes.
 */
import java.util.*;
import java.util.function.*;

public class InnerClassTest {
    interface Task {
        void run();
    }

    static class Outer {
        private final byte[] data;
        private final String name;
        private final List<Task> tasks = new ArrayList<>();

        Outer(int id, int dataSize) {
            this.data = new byte[dataSize];
            this.name = "outer-" + id;

            // Anonymous inner class capturing outer fields
            tasks.add(new Task() {
                @Override public void run() { data[0] = 1; }
            });

            // Lambda capturing outer field
            tasks.add(() -> System.out.println(name));

            // Method reference (no capture)
            tasks.add(System::gc);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        List<Outer> outers = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            outers.add(new Outer(i, 512));
        }

        // Standalone lambdas in arrays
        Runnable[] runnables = new Runnable[5_000];
        for (int i = 0; i < runnables.length; i++) {
            final int capture = i;
            runnables[i] = () -> { int x = capture * 2; };
        }

        // Supplier chain
        List<Supplier<String>> suppliers = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            final String s = "supplier-data-" + i;
            suppliers.add(() -> s);
        }

        System.out.println("InnerClassTest ready: outers=" + outers.size()
                + " runnables=" + runnables.length
                + " suppliers=" + suppliers.size());
        Thread.sleep(Long.MAX_VALUE);
    }
}
