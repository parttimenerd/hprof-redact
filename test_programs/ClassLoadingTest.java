/**
 * Test case with class loading and reflection.
 */
import java.lang.reflect.*;
import java.util.*;

public class ClassLoadingTest {
    static class ClassHolder {
        Class<?> clazz;
        Constructor<?>[] constructors;
        Method[] methods;
        Field[] fields;

        ClassHolder(Class<?> clazz) {
            this.clazz = clazz;
            this.constructors = safeGetDeclaredConstructors(clazz);
            this.methods = safeGetDeclaredMethods(clazz);
            this.fields = safeGetDeclaredFields(clazz);
        }

        private static Constructor<?>[] safeGetDeclaredConstructors(Class<?> clazz) {
            try {
                return clazz.getDeclaredConstructors();
            } catch (Throwable ignored) {
                return new Constructor<?>[0];
            }
        }

        private static Method[] safeGetDeclaredMethods(Class<?> clazz) {
            try {
                return clazz.getDeclaredMethods();
            } catch (Throwable ignored) {
                return new Method[0];
            }
        }

        private static Field[] safeGetDeclaredFields(Class<?> clazz) {
            try {
                return clazz.getDeclaredFields();
            } catch (Throwable ignored) {
                return new Field[0];
            }
        }
    }

    public static void main(String[] args) throws Exception {
        List<ClassHolder> classes = new ArrayList<>();

        // Load and reflect on various classes
        Class<?>[] testClasses = {
            String.class, Object.class, ArrayList.class, HashMap.class,
            Thread.class, Exception.class, Throwable.class,
            java.util.LinkedList.class, java.util.TreeMap.class,
            java.util.HashSet.class, java.util.concurrent.ConcurrentHashMap.class
        };

        for (Class<?> clazz : testClasses) {
            for (int i = 0; i < 10; i++) {
                classes.add(new ClassHolder(clazz));
            }
        }

        System.out.println("Class loading test created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}