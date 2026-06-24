package me.bechberger.hprof.diagnose;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/** Tracks top-N entries by a long size key. Thread-unsafe. */
public final class TopNTracker<T> {
    private final int maxSize;
    private final PriorityQueue<Entry<T>> heap; // min-heap: smallest at top

    public record Entry<T>(long size, T value) implements Comparable<Entry<T>> {
        public int compareTo(Entry<T> o) { return Long.compare(this.size, o.size); }
    }

    public TopNTracker(int maxSize) {
        this.maxSize = maxSize;
        this.heap = new PriorityQueue<>(maxSize + 1);
    }

    /** Add an entry. If the heap is full and size <= smallest, ignored. */
    public void add(long size, T value) {
        if (heap.size() < maxSize) {
            heap.offer(new Entry<>(size, value));
        } else if (!heap.isEmpty() && size > heap.peek().size()) {
            heap.poll();
            heap.offer(new Entry<>(size, value));
        }
    }

    /** Returns entries sorted largest-first. */
    public List<Entry<T>> topEntries() {
        List<Entry<T>> result = new ArrayList<>(heap);
        result.sort((a, b) -> Long.compare(b.size(), a.size()));
        return result;
    }
}
