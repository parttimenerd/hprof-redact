package me.bechberger.hprof.diagnose;

/** Primitive long open-addressing hash set. add() returns false on OOM instead of throwing. */
public final class LongHashSet {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] table;
    private int size;
    private int threshold;

    public LongHashSet(int initialCapacity) {
        int cap = nextPowerOf2(Math.max(initialCapacity, 16));
        table = new long[cap];
        java.util.Arrays.fill(table, EMPTY);
        threshold = cap * 3 / 4;
    }

    /** Returns true if added (was absent), false if already present or OOM. */
    public boolean add(long value) {
        if (value == EMPTY) value = Long.MAX_VALUE; // remap sentinel
        if (size >= threshold && !tryGrow()) return false;
        return insert(table, value);
    }

    public boolean contains(long value) {
        if (value == EMPTY) value = Long.MAX_VALUE;
        int i = index(value, table.length);
        while (table[i] != EMPTY) {
            if (table[i] == value) return true;
            i = (i + 1) & (table.length - 1);
        }
        return false;
    }

    public int size() { return size; }

    private boolean tryGrow() {
        int newCap = table.length * 2;
        long[] newTable;
        try {
            newTable = new long[newCap];
        } catch (OutOfMemoryError e) {
            return false;
        }
        java.util.Arrays.fill(newTable, EMPTY);
        for (long v : table) {
            if (v != EMPTY) rehash(newTable, v);
        }
        table = newTable;
        threshold = newCap * 3 / 4;
        return true;
    }

    /** Place value into table without touching size (used during rehash). */
    private static void rehash(long[] t, long value) {
        int i = index(value, t.length);
        while (t[i] != EMPTY) {
            i = (i + 1) & (t.length - 1);
        }
        t[i] = value;
    }

    private boolean insert(long[] t, long value) {
        int i = index(value, t.length);
        while (t[i] != EMPTY) {
            if (t[i] == value) return false;
            i = (i + 1) & (t.length - 1);
        }
        t[i] = value;
        size++;
        return true;
    }

    private static int index(long value, int len) {
        int h = Long.hashCode(value);
        return (h ^ (h >>> 16)) & (len - 1);
    }

    private static int nextPowerOf2(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }
}
