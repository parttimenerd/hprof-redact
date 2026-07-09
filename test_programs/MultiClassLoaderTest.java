/**
 * Multiple classloader test.
 * Creates several child classloaders each holding their own object graphs,
 * producing distinct classloader subtrees in the dominator tree.
 */
import java.net.URLClassLoader;
import java.net.URL;
import java.io.File;
import java.util.*;

public class MultiClassLoaderTest {
    static class Tenant {
        final String name;
        final URLClassLoader loader;
        final List<byte[]> objects = new ArrayList<>();

        Tenant(String name) throws Exception {
            this.name = name;
            // Create a child classloader using the current class's source URL
            URL url = MultiClassLoaderTest.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            this.loader = new URLClassLoader(new URL[]{url},
                    ClassLoader.getSystemClassLoader().getParent());
            for (int i = 0; i < 2_000; i++) {
                objects.add(new byte[256]);
            }
        }
    }

    static class Singleton {
        final byte[] bigBlob = new byte[2_000_000];
        final int[] counters = new int[100_000];
    }

    public static void main(String[] args) throws Exception {
        Singleton s = new Singleton();

        List<Tenant> tenants = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tenants.add(new Tenant("tenant-" + i));
        }

        System.out.println("MultiClassLoaderTest ready: " + tenants.size()
                + " tenants, singleton blob=" + s.bigBlob.length + " bytes");
        Thread.sleep(Long.MAX_VALUE);
    }
}
