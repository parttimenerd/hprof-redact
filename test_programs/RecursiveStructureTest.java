/**
 * Test case with recursive data structures.
 * Creates a tree structure with cycles.
 */
public class RecursiveStructureTest {
    static class TreeNode {
        String value;
        TreeNode left;
        TreeNode right;
        Object metadata;

        TreeNode(String value) {
            this.value = value;
            this.metadata = new Object();
        }
    }

    static class Graph {
        String id;
        java.util.List<Graph> neighbors;

        Graph(String id) {
            this.id = id;
            this.neighbors = new java.util.ArrayList<>();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // Build a binary tree
        TreeNode root = new TreeNode("root");
        buildTree(root, 0, 7);

        // Build a graph with cycles
        Graph[] nodes = new Graph[30];
        for (int i = 0; i < 30; i++) {
            nodes[i] = new Graph("node_" + i);
        }

        // Add random connections
        for (int i = 0; i < 30; i++) {
            for (int j = 0; j < 3; j++) {
                int neighbor = (i + j + 1) % 30;
                nodes[i].neighbors.add(nodes[neighbor]);
            }
        }

        System.out.println("Recursive structures created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }

    static void buildTree(TreeNode node, int depth, int maxDepth) {
        if (depth >= maxDepth) return;
        node.left = new TreeNode("left_" + depth);
        node.right = new TreeNode("right_" + depth);
        buildTree(node.left, depth + 1, maxDepth);
        buildTree(node.right, depth + 1, maxDepth);
    }
}