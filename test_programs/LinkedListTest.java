/**
 * Test case with linked list structure.
 * Creates a chain of objects referencing each other.
 */
import java.util.LinkedList;
import java.util.ArrayList;

public class LinkedListTest {
    static class Node {
        String data;
        int id;
        Node next;
        ArrayList<Object> references;

        Node(String data, int id) {
            this.data = data;
            this.id = id;
            this.references = new ArrayList<>();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        LinkedList<String> linkedList = new LinkedList<>();
        for (int i = 0; i < 500; i++) {
            linkedList.add("Item_" + i);
        }

        // Create linked nodes
        Node head = new Node("head", 0);
        Node current = head;
        for (int i = 1; i < 100; i++) {
            Node newNode = new Node("node_" + i, i);
            current.next = newNode;
            current.references.add(new Object());
            current.references.add("reference_" + i);
            current = newNode;
        }

        System.out.println("LinkedList and nodes created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}