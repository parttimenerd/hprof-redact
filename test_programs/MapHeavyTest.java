/**
 * HashMap and TreeMap stress test.
 * Tests histogram accuracy for collection internals:
 * HashMap.Entry[], HashMap.Node[], TreeMap.Entry nodes.
 */
import java.util.*;

public class MapHeavyTest {
    public static void main(String[] args) throws InterruptedException {
        // Large HashMap: stresses HashMap$Node[] and HashMap$Node entries
        Map<String, byte[]> hashMap = new HashMap<>(100_000);
        for (int i = 0; i < 80_000; i++) {
            hashMap.put("key-" + i, new byte[64]);
        }

        // TreeMap: stresses TreeMap$Entry nodes (each has left/right/parent refs)
        TreeMap<Integer, String> treeMap = new TreeMap<>();
        for (int i = 0; i < 50_000; i++) {
            treeMap.put(i, "value-" + i);
        }

        // LinkedHashMap: has additional before/after links per entry
        LinkedHashMap<String, Integer> linkedMap = new LinkedHashMap<>(20_000);
        for (int i = 0; i < 15_000; i++) {
            linkedMap.put("lhm-" + i, i);
        }

        // IdentityHashMap: uses Object[] internally, different layout
        IdentityHashMap<Object, Object> identMap = new IdentityHashMap<>(5_000);
        for (int i = 0; i < 4_000; i++) {
            identMap.put(new Object(), new byte[32]);
        }

        System.out.println("MapHeavyTest ready:"
                + " hashMap=" + hashMap.size()
                + " treeMap=" + treeMap.size()
                + " linkedMap=" + linkedMap.size()
                + " identMap=" + identMap.size());
        Thread.sleep(Long.MAX_VALUE);
    }
}
