/**
 * Test case with many small objects and fragmentation.
 */
public class FragmentationTest {
    static class Tiny {
        byte value;
    }

    static class Small {
        long id;
        String name;
    }

    static class Medium {
        double[] data;
        int count;
    }

    public static void main(String[] args) throws InterruptedException {
        Object[] mixed = new Object[50000];

        int idx = 0;
        // Add many tiny objects
        for (int i = 0; i < 10000; i++) {
            mixed[idx++] = new Tiny();
        }

        // Add small objects
        for (int i = 0; i < 10000; i++) {
            Small s = new Small();
            s.id = i;
            s.name = "Small_" + i;
            mixed[idx++] = s;
        }

        // Add medium objects
        for (int i = 0; i < 30000; i++) {
            Medium m = new Medium();
            m.data = new double[100];
            m.count = i;
            mixed[idx++] = m;
        }

        System.out.println("Fragmentation test created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}