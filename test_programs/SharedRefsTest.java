/**
 * Object graph with shared references (diamond pattern).
 * Many objects share sub-objects — tests that retained sizes
 * correctly attribute shared objects to the lowest common dominator,
 * not double-count them.
 */
import java.util.*;

public class SharedRefsTest {
    static class Leaf {
        final byte[] data;
        Leaf(int size) { data = new byte[size]; }
    }

    static class Node {
        final int id;
        Leaf sharedLeaf;   // shared with other nodes
        Leaf privateLeaf;  // owned exclusively
        Node(int id, Leaf shared) {
            this.id = id;
            this.sharedLeaf = shared;
            this.privateLeaf = new Leaf(256);
        }
    }

    static class Root {
        final Leaf bigShared;          // shared by all nodes in this root
        final List<Node> nodes = new ArrayList<>();

        Root(int nodeCount, int sharedSize) {
            bigShared = new Leaf(sharedSize);
            for (int i = 0; i < nodeCount; i++) {
                nodes.add(new Node(i, bigShared));
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 10 roots, each with 1000 nodes sharing one large leaf
        List<Root> roots = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            roots.add(new Root(1_000, 100_000)); // 100 KB shared per root
        }

        // Cross-root sharing: one leaf shared between all roots
        Leaf globalShared = new Leaf(500_000); // 500 KB shared globally
        for (Root r : roots) {
            r.nodes.get(0).sharedLeaf = globalShared;
        }

        System.out.println("SharedRefsTest ready: roots=" + roots.size()
                + " nodesPerRoot=1000 globalShared=" + globalShared.data.length);
        Thread.sleep(Long.MAX_VALUE);
    }
}
