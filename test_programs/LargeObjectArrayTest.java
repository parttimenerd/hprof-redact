/**
 * Large Object[] stress test.
 * A singleton holder owns a large Object[] and mixed sub-arrays,
 * so Object[]/retained should be nearly all of the heap.
 */
public class LargeObjectArrayTest {
    static class Holder {
        Object[] primary;     // large top-level array
        Object[][] matrix;    // array of arrays
        String[] strings;

        Holder() {
            primary = new Object[50_000];
            for (int i = 0; i < primary.length; i++) {
                primary[i] = new byte[100];
            }
            matrix = new Object[200][250];
            for (int i = 0; i < matrix.length; i++) {
                for (int j = 0; j < matrix[i].length; j++) {
                    matrix[i][j] = new long[10];
                }
            }
            strings = new String[10_000];
            for (int i = 0; i < strings.length; i++) {
                strings[i] = "entry-" + i;
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Holder h = new Holder();
        System.out.println("LargeObjectArrayTest ready. primary=" + h.primary.length
                + " matrix=" + h.matrix.length + "x" + h.matrix[0].length);
        Thread.sleep(Long.MAX_VALUE);
    }
}
