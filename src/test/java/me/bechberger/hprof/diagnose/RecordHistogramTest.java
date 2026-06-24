package me.bechberger.hprof.diagnose;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecordHistogramTest {

    @Test
    void recordsAccumulateCorrectly() {
        RecordHistogram hist = new RecordHistogram();
        hist.record(1, 100);
        hist.record(1, 200);
        hist.record(1, 50);

        List<RecordHistogram.Entry> entries = hist.entries();
        assertEquals(1, entries.size());
        RecordHistogram.Entry e = entries.get(0);
        assertEquals(1, e.tag());
        assertEquals(3L, e.count());
        assertEquals(350L, e.totalBytes());
    }

    @Test
    void entriesSortedByTotalBytesDescending() {
        RecordHistogram hist = new RecordHistogram();
        hist.record(1, 10);   // tag 1: total = 10
        hist.record(2, 500);  // tag 2: total = 500
        hist.record(3, 250);  // tag 3: total = 250
        hist.record(2, 100);  // tag 2: total = 600

        List<RecordHistogram.Entry> entries = hist.entries();
        assertEquals(3, entries.size());
        assertEquals(2, entries.get(0).tag());
        assertEquals(600L, entries.get(0).totalBytes());
        assertEquals(3, entries.get(1).tag());
        assertEquals(250L, entries.get(1).totalBytes());
        assertEquals(1, entries.get(2).tag());
        assertEquals(10L, entries.get(2).totalBytes());
    }

    @Test
    void multipleTagsTrackedIndependently() {
        RecordHistogram hist = new RecordHistogram();
        hist.record(10, 1000);
        hist.record(20, 2000);
        hist.record(10, 500);

        List<RecordHistogram.Entry> entries = hist.entries();
        assertEquals(2, entries.size());

        RecordHistogram.Entry tag10 = entries.stream().filter(e -> e.tag() == 10).findFirst().orElseThrow();
        RecordHistogram.Entry tag20 = entries.stream().filter(e -> e.tag() == 20).findFirst().orElseThrow();

        assertEquals(2L, tag10.count());
        assertEquals(1500L, tag10.totalBytes());
        assertEquals(1L, tag20.count());
        assertEquals(2000L, tag20.totalBytes());
    }

    @Test
    void emptyHistogram() {
        RecordHistogram hist = new RecordHistogram();
        assertTrue(hist.entries().isEmpty());
    }
}
