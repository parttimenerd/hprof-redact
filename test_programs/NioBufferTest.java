/**
 * Test case with byte buffer and NIO structures.
 */
import java.nio.*;
import java.util.*;

public class NioBufferTest {
    public static void main(String[] args) throws InterruptedException {
        List<ByteBuffer> directBuffers = new ArrayList<>();
        List<ByteBuffer> heapBuffers = new ArrayList<>();

        // Create direct buffers (off-heap memory)
        for (int i = 0; i < 50; i++) {
            ByteBuffer bb = ByteBuffer.allocateDirect(1024 * 100); // 100 KB each
            directBuffers.add(bb);
        }

        // Create heap buffers
        for (int i = 0; i < 100; i++) {
            ByteBuffer bb = ByteBuffer.allocate(1024 * 50); // 50 KB each
            heapBuffers.add(bb);
        }

        // Create typed buffers
        List<IntBuffer> intBuffers = new ArrayList<>();
        List<LongBuffer> longBuffers = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            intBuffers.add(ByteBuffer.allocate(1024 * 20).asIntBuffer());
            longBuffers.add(ByteBuffer.allocate(1024 * 20).asLongBuffer());
        }

        System.out.println("NIO buffer test created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}