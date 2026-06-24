package me.bechberger.hprof.diagnose;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopNTrackerTest {

    @Test
    void keepsExactlyTopN() {
        TopNTracker<String> tracker = new TopNTracker<>(3);
        tracker.add(10, "ten");
        tracker.add(5, "five");
        tracker.add(20, "twenty");
        tracker.add(15, "fifteen");
        tracker.add(1, "one");
        tracker.add(100, "hundred");

        List<TopNTracker.Entry<String>> top = tracker.topEntries();
        assertEquals(3, top.size());
        assertEquals(100L, top.get(0).size());
        assertEquals(20L, top.get(1).size());
        assertEquals(15L, top.get(2).size());
    }

    @Test
    void topEntriesReturnedDescending() {
        TopNTracker<Integer> tracker = new TopNTracker<>(5);
        tracker.add(3, 3);
        tracker.add(1, 1);
        tracker.add(4, 4);
        tracker.add(2, 2);
        tracker.add(5, 5);

        List<TopNTracker.Entry<Integer>> top = tracker.topEntries();
        assertEquals(5, top.size());
        for (int i = 0; i < top.size() - 1; i++) {
            assertTrue(top.get(i).size() >= top.get(i + 1).size(),
                    "Expected descending order at index " + i);
        }
    }

    @Test
    void nEqualsOne() {
        TopNTracker<String> tracker = new TopNTracker<>(1);
        tracker.add(42, "a");
        tracker.add(7, "b");
        tracker.add(99, "c");
        tracker.add(50, "d");

        List<TopNTracker.Entry<String>> top = tracker.topEntries();
        assertEquals(1, top.size());
        assertEquals(99L, top.get(0).size());
        assertEquals("c", top.get(0).value());
    }

    @Test
    void fewerItemsThanN() {
        TopNTracker<String> tracker = new TopNTracker<>(10);
        tracker.add(5, "a");
        tracker.add(3, "b");

        List<TopNTracker.Entry<String>> top = tracker.topEntries();
        assertEquals(2, top.size());
        assertEquals(5L, top.get(0).size());
        assertEquals(3L, top.get(1).size());
    }
}
