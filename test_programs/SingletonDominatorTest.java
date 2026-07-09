/**
 * Singleton dominator test.
 * A single static object dominates >90% of the heap.
 * Tests that the dominator tree correctly attributes retained sizes
 * to a non-Thread, non-classloader root.
 */
import java.util.*;

public class SingletonDominatorTest {
    static class Node {
        final int id;
        final byte[] data;
        Node next;

        Node(int id, int dataSize) {
            this.id = id;
            this.data = new byte[dataSize];
        }
    }

    static class BigSingleton {
        // Linked list of nodes — all dominated by this singleton
        final Node head;
        // Large flat arrays
        final long[] longArray = new long[100_000];
        final double[] doubleArray = new double[100_000];
        final Object[] objectArray;
        final Map<String, byte[]> blobMap = new HashMap<>();

        BigSingleton() {
            // Build linked list: 10k nodes x 1KB each = ~10 MB
            Node current = null;
            for (int i = 9_999; i >= 0; i--) {
                Node n = new Node(i, 1_024);
                n.next = current;
                current = n;
            }
            head = current;

            // Object array: 5k entries x ~200 bytes = ~1 MB
            objectArray = new Object[5_000];
            for (int i = 0; i < objectArray.length; i++) {
                objectArray[i] = new byte[200];
            }

            // Blob map: 1k entries x 4KB = ~4 MB
            for (int i = 0; i < 1_000; i++) {
                blobMap.put("key-" + i, new byte[4_096]);
            }
        }
    }

    // Small background noise so the singleton isn't literally everything
    static List<Object> noise = new ArrayList<>();

    public static void main(String[] args) throws InterruptedException {
        BigSingleton singleton = new BigSingleton();

        // ~200 KB of noise objects
        for (int i = 0; i < 200; i++) {
            noise.add(new byte[1_024]);
        }

        System.out.println("SingletonDominatorTest ready: linked list nodes=10000"
                + " objectArray=" + singleton.objectArray.length
                + " blobMap=" + singleton.blobMap.size());
        Thread.sleep(Long.MAX_VALUE);
    }
}
