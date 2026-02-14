/**
 * Test case with various array types.
 * Creates primitive and object arrays.
 */
public class ArrayTest {
    public static void main(String[] args) throws InterruptedException {
        // Primitive arrays
        int[] intArray = new int[1000];
        long[] longArray = new long[500];
        byte[] byteArray = new byte[2000];
        char[] charArray = new char[1500];
        double[] doubleArray = new double[800];
        boolean[] boolArray = new boolean[600];

        // Initialize with some data
        for (int i = 0; i < intArray.length; i++) {
            intArray[i] = i;
            longArray[i % longArray.length] = i * 1000L;
            byteArray[i % byteArray.length] = (byte) (i % 256);
            charArray[i % charArray.length] = (char) ('A' + (i % 26));
            doubleArray[i % doubleArray.length] = i * 3.14;
            boolArray[i % boolArray.length] = i % 2 == 0;
        }

        // Object arrays
        String[] strings = new String[100];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = "String_" + i;
        }

        System.out.println("Arrays created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}