/**
 * Finalizer-heavy test.
 * Creates many objects with finalizers so they appear in MAT's
 * java.lang.ref.Finalizer GC root chain.
 */
public class FinalizerTest {
    static class Finalizable {
        private final int id;
        private final byte[] payload;

        Finalizable(int id) {
            this.id = id;
            this.payload = new byte[512];
        }

        @Override
        protected void finalize() {
            // keep reference alive so finalize is meaningful
            payload[0] = (byte) id;
        }
    }

    static class NormalObject {
        String name;
        int value;
        NormalObject(String name, int value) { this.name = name; this.value = value; }
    }

    public static void main(String[] args) throws InterruptedException {
        // Large number of finalizable objects
        Finalizable[] finaliz = new Finalizable[5_000];
        for (int i = 0; i < finaliz.length; i++) {
            finaliz[i] = new Finalizable(i);
        }

        // Normal objects for contrast
        NormalObject[] normal = new NormalObject[5_000];
        for (int i = 0; i < normal.length; i++) {
            normal[i] = new NormalObject("obj-" + i, i);
        }

        System.out.println("FinalizerTest ready: " + finaliz.length
                + " finalizable + " + normal.length + " normal objects");
        Thread.sleep(Long.MAX_VALUE);
    }
}
