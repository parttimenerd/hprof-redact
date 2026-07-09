/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 *
 * Synthetic HashMap-heavy heap producer for hprof-redact MAT-heap-prediction calibration.
 *
 * Designed to stress every dimension MatMemoryModel cares about:
 *   - Many instances (objects)              → id-index, o2c, a2s, dominator transient
 *   - High outbound reference density       → outbound + inbound peak in dominator phase
 *   - Many strings, varying lengths         → string table sizing
 *   - Many object arrays (Entry[]):
 *       HashMap.Entry[] tables are object arrays — they're huge ref aggregators
 *   - Modest class count                    → keeps classCache small so other components
 *                                              dominate; combine with SpringPetclinic for
 *                                              high-class-count coverage
 *
 * Configurable via JVM system properties (-D...):
 *   -Dmaps=N            number of top-level HashMaps                        (default 200)
 *   -Dentries=N         entries per map                                     (default 50000)
 *   -DkeyLen=N          average key string length                           (default 16)
 *   -DvalueDepth=N      nesting depth of each value object graph            (default 3)
 *   -DvalueFanout=N     children per node in the value object graph         (default 4)
 *   -Dduration=SEC      seconds to stay alive after allocation              (default 600)
 *   -DdumpPath=PATH     if set, dump to this path then exit (otherwise idle)
 *
 * Total resident heap ≈ maps × entries × (keyLen + valueGraphSize) bytes,
 * with valueGraphSize ≈ (valueFanout^valueDepth) × ~40 B/node.
 *
 * Defaults target ~1.2 GB resident; raise '-Dentries' or '-Dmaps' to grow.
 */

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import javax.management.MBeanServer;

public final class HashMapHeavyDump {

    static final int    MAPS         = Integer.getInteger("maps", 200);
    static final int    ENTRIES      = Integer.getInteger("entries", 50_000);
    static final int    KEY_LEN      = Integer.getInteger("keyLen", 16);
    static final int    VALUE_DEPTH  = Integer.getInteger("valueDepth", 3);
    static final int    VALUE_FANOUT = Integer.getInteger("valueFanout", 4);
    static final int    DURATION_SEC = Integer.getInteger("duration", 600);
    static final String DUMP_PATH    = System.getProperty("dumpPath");

    static final class Node {
        String label;
        long id;
        int depth;
        Node[] children;
        Map<String, String> attributes;

        Node(String label, long id, int depth, Node[] children) {
            this.label = label;
            this.id = id;
            this.depth = depth;
            this.children = children;
            // small per-node attribute map — gives a third HashMap path beyond the top-level ones
            this.attributes = new HashMap<>(4);
            attributes.put("created", String.valueOf(id));
            attributes.put("kind", depth == 0 ? "leaf" : "branch");
        }
    }

    static Node buildGraph(int depth, long idSeed) {
        long id = idSeed;
        if (depth == 0) {
            return new Node("leaf-" + id, id, 0, new Node[0]);
        }
        Node[] kids = new Node[VALUE_FANOUT];
        for (int i = 0; i < VALUE_FANOUT; i++) {
            kids[i] = buildGraph(depth - 1, id * 31L + i);
        }
        return new Node("node-" + id, id, depth, kids);
    }

    static String randomKey(int idx) {
        char[] buf = new char[KEY_LEN];
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < KEY_LEN; i++) {
            buf[i] = (char) ('a' + rng.nextInt(26));
        }
        // ensure uniqueness even on length-1 keys
        return new String(buf) + "#" + idx;
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        System.out.printf("HashMapHeavyDump: maps=%d entries=%d keyLen=%d valueDepth=%d valueFanout=%d%n",
                MAPS, ENTRIES, KEY_LEN, VALUE_DEPTH, VALUE_FANOUT);

        List<HashMap<String, Node>> topMaps = new ArrayList<>(MAPS);
        long globalId = 0;
        for (int m = 0; m < MAPS; m++) {
            HashMap<String, Node> map = new HashMap<>(ENTRIES * 2);
            for (int e = 0; e < ENTRIES; e++) {
                String key = randomKey(e);
                Node value = buildGraph(VALUE_DEPTH, globalId++);
                map.put(key, value);
            }
            topMaps.add(map);
            if ((m + 1) % 10 == 0) {
                long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >>> 20;
                System.out.printf("  populated %d/%d maps, used=%d MB%n", m + 1, MAPS, usedMb);
            }
        }

        System.gc();
        Thread.sleep(200);
        long usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.printf("Allocated in %.1f s, used heap = %d MB%n",
                (System.currentTimeMillis() - t0) / 1000.0, usedBytes >>> 20);

        if (DUMP_PATH != null) {
            dump(DUMP_PATH);
            System.out.println("Dumped to " + DUMP_PATH);
            // touch references so GC doesn't drop them before the dump finished
            if (topMaps.isEmpty()) throw new RuntimeException("dead");
            return;
        }

        System.out.printf("Idling for %d s (use 'jcmd <pid> GC.heap_dump <path>' to capture)%n", DURATION_SEC);
        Thread.sleep(DURATION_SEC * 1000L);

        // keep references alive
        if (topMaps.isEmpty()) throw new RuntimeException("dead");
    }

    static void dump(String path) throws Exception {
        Path p = Path.of(path);
        Files.deleteIfExists(p);
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        HotSpotDiagnosticMXBean mx = ManagementFactory.newPlatformMXBeanProxy(
                server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
        mx.dumpHeap(path, true);
    }
}
