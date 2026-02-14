/**
 * Test case with various Java collections.
 * Tests HashMap, HashSet, TreeMap, etc.
 */
import java.util.*;

public class CollectionsTest {
    static class Person {
        String name;
        int age;
        String email;

        Person(String name, int age, String email) {
            this.name = name;
            this.age = age;
            this.email = email;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Person)) return false;
            Person p = (Person) o;
            return name.equals(p.name);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // HashMap
        Map<String, Person> peopleMap = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            peopleMap.put("person_" + i, new Person("Name_" + i, 20 + i % 50, "email" + i + "@example.com"));
        }

        // HashSet
        Set<String> stringSet = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            stringSet.add("string_" + i);
        }

        // TreeMap
        TreeMap<Integer, String> treeMap = new TreeMap<>();
        for (int i = 0; i < 150; i++) {
            treeMap.put(i, "value_" + i);
        }

        // LinkedHashMap
        LinkedHashMap<String, Integer> linkedMap = new LinkedHashMap<>();
        for (int i = 0; i < 80; i++) {
            linkedMap.put("key_" + i, i * 100);
        }

        // ArrayList
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            list.add(new Person("ListPerson_" + i, i % 80, "list" + i + "@test.com"));
        }

        System.out.println("Collections created. Press Ctrl+C to exit...");
        Thread.sleep(Long.MAX_VALUE);
    }
}