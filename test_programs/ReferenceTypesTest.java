/**
 * Test case with weak and soft references.
 */
import java.lang.ref.*;
import java.util.*;

public class ReferenceTypesTest {
    static class DataHolder {
        byte[] data;
        String info;

        DataHolder(int size, String info) {
            this.data = new byte[size];
            this.info = info;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        List<WeakReference<DataHolder>> weakRefs = new ArrayList<>();
        List<SoftReference<DataHolder>> softRefs = new ArrayList<>();
        List<PhantomReference<DataHolder>> phantomRefs = new ArrayList<>();
        ReferenceQueue<DataHolder> queue = new ReferenceQueue<>();

        // Create weak references
        for (int i = 0; i < 100; i++) {
            weakRefs.add(new WeakReference<>(new DataHolder(1000, "weak_" + i)));
        }

        // Create soft references
        for (int i = 0; i < 100; i++) {
            softRefs.add(new SoftReference<>(new DataHolder(5000, "soft_" + i)));
        }

        // Create phantom references
        DataHolder[] holders = new DataHolder[50];
        for (int i = 0; i < holders.length; i++) {
            holders[i] = new DataHolder(2000, "phantom_" + i);
            phantomRefs.add(new PhantomReference<>(holders[i], queue));
        }

        System.out.println("Reference types test created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}