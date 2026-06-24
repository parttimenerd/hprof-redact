package me.bechberger.hprof.diagnose;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RecordHistogram {
    private final Map<Integer, long[]> stats = new HashMap<>(); // tag -> [count, totalBytes]

    public void record(int tag, long bytes) {
        stats.computeIfAbsent(tag, k -> new long[2]);
        long[] s = stats.get(tag);
        s[0]++;
        s[1] += bytes;
    }

    /** Returns all entries sorted by totalBytes descending. */
    public List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (var e : stats.entrySet()) {
            result.add(new Entry(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        result.sort((a, b) -> Long.compare(b.totalBytes(), a.totalBytes()));
        return result;
    }

    public record Entry(int tag, long count, long totalBytes) {}
}
