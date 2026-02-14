/**
 * Test case that creates exception objects and stack traces.
 */
public class ExceptionTest {
    static class CustomException extends Exception {
        private String context;

        CustomException(String message, String context) {
            super(message);
            this.context = context;
        }
    }

    public static void methodA() throws CustomException {
        throw new CustomException("Error in methodA", "context_a");
    }

    public static void methodB() {
        try {
            methodA();
        } catch (CustomException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // Create multiple exceptions
        Exception[] exceptions = new Exception[50];
        for (int i = 0; i < 50; i++) {
            try {
                methodB();
            } catch (Exception e) {
                exceptions[i] = e;
            }
        }

        System.out.println("Exceptions created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}