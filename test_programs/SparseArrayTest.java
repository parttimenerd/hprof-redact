/**
 * Sparse large primitive array test.
 * Creates very large int[], long[], double[], and boolean[] arrays
 * that are mostly zero/false, plus some fully populated ones.
 * Tests that primitive array shallow sizes and retained sizes
 * are computed correctly independent of content.
 */
public class SparseArrayTest {
    public static void main(String[] args) throws InterruptedException {
        // Sparse: 1M elements, only every 100th slot populated meaningfully
        long[] sparseLong = new long[1_000_000];
        for (int i = 0; i < sparseLong.length; i += 100) {
            sparseLong[i] = i * 31L;
        }

        // Sparse int array
        int[] sparseInt = new int[2_000_000];
        for (int i = 0; i < sparseInt.length; i += 50) {
            sparseInt[i] = i;
        }

        // Dense double array
        double[] denseDbl = new double[500_000];
        for (int i = 0; i < denseDbl.length; i++) {
            denseDbl[i] = Math.PI * i;
        }

        // Dense boolean array
        boolean[] denseBool = new boolean[4_000_000];
        for (int i = 0; i < denseBool.length; i++) {
            denseBool[i] = (i % 3) != 0;
        }

        // Array of arrays: shallow vs retained difference
        long[][] jaggedLong = new long[1_000][];
        for (int i = 0; i < jaggedLong.length; i++) {
            jaggedLong[i] = new long[i + 1]; // sizes 1..1000
        }

        System.out.println("SparseArrayTest ready:"
                + " sparseLong=" + sparseLong.length
                + " sparseInt=" + sparseInt.length
                + " denseDbl=" + denseDbl.length
                + " denseBool=" + denseBool.length
                + " jaggedLong=" + jaggedLong.length);
        Thread.sleep(Long.MAX_VALUE);
    }
}
