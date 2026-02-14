/**
 * Test case with large string allocations and string pool effects.
 */
public class StringPoolTest {
    public static void main(String[] args) throws InterruptedException {
        // Create many strings to test string pool
        String[] strings = new String[10000];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = new String("String_" + (i % 100)); // Many duplicates
        }

        // Intern some strings
        String[] internedStrings = new String[100];
        for (int i = 0; i < internedStrings.length; i++) {
            internedStrings[i] = ("Interned_" + i).intern();
        }

        System.out.println("String pool test created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}