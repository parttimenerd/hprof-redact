/**
 * Test case with multiple threads.
 * Creates threads with different states and data.
 */
public class ThreadTest {
    static class WorkerThread extends Thread {
        private String threadName;
        private byte[] buffer;
        private volatile boolean running = true;

        WorkerThread(String name, int bufferSize) {
            super(name);
            this.threadName = name;
            this.buffer = new byte[bufferSize];
            setDaemon(false);
        }

        @Override
        public void run() {
            try {
                while (running) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Thread[] threads = new Thread[20];
        for (int i = 0; i < 20; i++) {
            threads[i] = new WorkerThread("Worker-" + i, 10000 * (i + 1));
            threads[i].start();
        }

        System.out.println("Threads created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}