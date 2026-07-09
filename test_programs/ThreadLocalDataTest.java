/**
 * Mixed thread and lock contention test.
 * Creates many threads each holding large local data structures,
 * tests that thread-local retained sizes are attributed to each thread.
 */
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class ThreadLocalDataTest {
    static final CountDownLatch ready = new CountDownLatch(1);

    static class WorkerThread extends Thread {
        private final int id;
        private final List<byte[]> localData = new ArrayList<>();
        private final Map<String, String> localMap = new HashMap<>();

        WorkerThread(int id) {
            super("worker-" + id);
            this.id = id;
            setDaemon(false);
        }

        @Override
        public void run() {
            // Allocate thread-local data
            for (int i = 0; i < 500; i++) {
                localData.add(new byte[1_024]); // 500 KB per thread
            }
            for (int i = 0; i < 1_000; i++) {
                localMap.put("thread-" + id + "-key-" + i, "value-" + i);
            }
            try {
                ready.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int numThreads = 10;
        WorkerThread[] threads = new WorkerThread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threads[i] = new WorkerThread(i);
            threads[i].start();
        }
        // Wait for all threads to allocate their data
        Thread.sleep(500);

        System.out.println("ThreadLocalDataTest ready: " + numThreads + " threads"
                + " each with 500KB local data");
        Thread.sleep(Long.MAX_VALUE);
    }
}
