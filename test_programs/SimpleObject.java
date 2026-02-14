/**
 * Simple object creation test case.
 * Creates a few objects and keeps them in memory.
 */
public class SimpleObject {
    private String name;
    private int value;
    private long timestamp;

    public SimpleObject(String name, int value) {
        this.name = name;
        this.value = value;
        this.timestamp = System.currentTimeMillis();
    }

    public static void main(String[] args) throws InterruptedException {
        Object[] objects = new Object[100];
        for (int i = 0; i < 100; i++) {
            objects[i] = new SimpleObject("Object_" + i, i * 10);
        }
        System.out.println("Objects created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}