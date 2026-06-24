package me.bechberger.hprof.diagnose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LongHashSetTest {

    @Test
    void addAndContainsBasic() {
        LongHashSet set = new LongHashSet(16);
        assertTrue(set.add(42L));
        assertTrue(set.contains(42L));
        assertFalse(set.contains(43L));
    }

    @Test
    void duplicateAddReturnsFalse() {
        LongHashSet set = new LongHashSet(16);
        assertTrue(set.add(100L));
        assertFalse(set.add(100L));
    }

    @Test
    void sizeTracking() {
        LongHashSet set = new LongHashSet(16);
        assertEquals(0, set.size());
        set.add(1L);
        assertEquals(1, set.size());
        set.add(2L);
        assertEquals(2, set.size());
        set.add(1L); // duplicate
        assertEquals(2, set.size());
    }

    @Test
    void manyElements() {
        LongHashSet set = new LongHashSet(16);
        int n = 1000;
        for (int i = 0; i < n; i++) {
            assertTrue(set.add((long) i));
        }
        assertEquals(n, set.size());
        for (int i = 0; i < n; i++) {
            assertTrue(set.contains((long) i));
        }
        assertFalse(set.contains((long) n));
    }

    @Test
    void negativeValues() {
        LongHashSet set = new LongHashSet(16);
        assertTrue(set.add(-1L));
        assertTrue(set.add(-999L));
        assertTrue(set.contains(-1L));
        assertTrue(set.contains(-999L));
        assertFalse(set.contains(-2L));
        assertEquals(2, set.size());
    }

    @Test
    void zeroValue() {
        LongHashSet set = new LongHashSet(16);
        assertTrue(set.add(0L));
        assertTrue(set.contains(0L));
        assertEquals(1, set.size());
    }
}
