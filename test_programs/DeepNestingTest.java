/**
 * Test case with deeply nested object structures.
 */
public class DeepNestingTest {
    static class DeepNode {
        DeepNode child;
        String[] data;

        DeepNode() {
            this.data = new String[10];
            for (int i = 0; i < data.length; i++) {
                data[i] = "data_" + i;
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // Create deeply nested structure
        DeepNode root = new DeepNode();
        DeepNode current = root;
        for (int i = 0; i < 500; i++) {
            current.child = new DeepNode();
            current = current.child;
        }

        System.out.println("Deep nesting test created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}