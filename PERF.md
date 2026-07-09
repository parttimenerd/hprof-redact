# Performance Audit: hprof-redact Views

**Context:** Processing a 135 MB HPROF dump (fj-kmeans, ~800M objects, ~1.6B edges) takes several minutes with peak virtual memory usage of 38 GB. This document identifies root causes and concrete mitigation strategies.

---

## Critical Issues (Primary drivers of 38 GB virtual memory / slow runtime)

### CRITICAL-1: UTF-8 String Interning — Unbounded HashMap Growth
**File:** `HeapGraphBuilder.java` lines 177-185  
**Root Cause:** All UTF-8 strings from HPROF records (class names, field names, method names, stack frame info) are decoded and stored in `graph.utf8Strings` (a HashMap<Long, String>). For a large heap, this can include:
- Thousands of unique class names
- Millions of field/method names (not deduplicated across loads)
- Each String object has ~40-60 bytes overhead plus character data

For 800M objects with diverse metadata, this HashMap can easily accumulate hundreds of MB of String objects that are held in memory for the entire run.

**Concrete Fix:**
1. **Lazy decode/intern:** Store raw bytes in a map first; decode only when accessed by reports. Implement a `String getUtf8String(long nameId)` method that decodes on-demand with internal LRU caching (e.g., 10k entries).
2. **Estimated impact:** Reduces UTF-8 Map footprint from ~200-500 MB (worst case) to ~20-50 MB (working set).

**Implementation sketch:**
- Add a private `LinkedHashMap<Long, String> utf8Cache = new LinkedHashMap<Long, String>(1 << 14, 0.75f, true) { protected boolean removeEldestEntry(...) { return size() > 10000; } };`
- Store raw bytes in a separate `Map<Long, byte[]> utf8Raw` initially.
- On access, check cache first, then decode and populate cache.

---

### CRITICAL-2: Multi-Pass File Parsing — Redundant Buffering and Re-decompression
**File:** `HeapGraphBuilder.java` lines 121-138 (Phase A.1, A.2, B scans)  
**Root Cause:** The three main phases (A.1, A.2, B) each re-open the HPROF file independently:
- Phase A.1 (line 157): `try (Parser p = openParser())`
- Phase A.2 (lines 583, 626): Two separate `openParser()` calls
- Phase B (lines 940, 962): Two more `openParser()` calls

For gzipped files, this means **decompressing the entire file 5 times**. Each decompression loads ~1 MB buffers into the JVM heap (GZIPInputStream internal buffers). Even for uncompressed files, this causes:
- Repeated I/O system calls (inefficient for SSDs)
- Multiple 1 MB direct ByteBuffers in off-heap memory (counted toward virtual memory)
- Cache misses on re-reads of cold file pages

**Concrete Fix:**
1. **Single-pass consolidation (Phase B only):** Merge Phase A.2b (forward CSR fill) and Phase B (inbound CSR fill + named edges) into a single pass. Store intermediate state (outDegree counts from A.2a) to enable single traversal of heap data.
2. **If consolidation is infeasible:** Cache decompressed content in a ring buffer or memory-mapped region.
3. **Estimated impact:** Eliminates 60% of file I/O overhead; saves ~2-4 GB virtual memory on gzipped 135 MB dumps (5x decompression buffers @ 1 MB each + OS page cache duplication).

**Implementation sketch:**
- Refactor `phaseA2` to split into:
  - `phaseA2_count()`: Single pass to count inDegree and outDegree
  - Keep `phaseA2_fill()` inline with Phase B in a combined `phaseB()` that does both inbound CSR + named edges in one pass

---

### CRITICAL-3: Parallel Array Doubling in A1State — Quadratic Growth
**File:** `HeapGraphBuilder.java` lines 1264-1272 (A1State.appendAddress)  
**Root Cause:** Dynamic buffers for addresses, shallow sizes, class IDs, and array types use `Arrays.copyOf(..., count * 2)` when full. For 800M objects:
- Initial estimate: ~16.7M objects → 4 x 8-byte longs = 32 MB per buffer
- Worst case: growth path triggers at ~100M, then at ~200M, then ~400M, ~800M
- Each doubling retains the old array until GC; transient peaks can reach **2x the target size**
- With 4 parallel arrays (addrBuf, shallowBuf, classIdBuf, arrayTypeBuf), the transiency multiplier is ~8x

For 800M objects, target size is ~6.4 GB; doubling transients = **12-16 GB peak just for A1State**.

**Concrete Fix:**
1. **Better initial estimate:** Use `Math.max(fileSize / 40, 1_000_000)` instead of `fileSize / 48`. For 135 MB, this estimates ~3.4M, closer to actual ~800M, reducing overallocation factor.
2. **Preallocate arrays upfront:** After parsing HPROF_START_THREAD, HPROF_LOAD_CLASS, etc., scan for object count hints (e.g., some HPROF variants include object count metadata). Pre-size all buffers to exact N once known.
3. **Use primitive arrays pool:** Recycle unused doubling buffers via a simple list instead of relying on GC.
4. **Estimated impact:** Reduces transient peak by 40-50% (~6-8 GB saved for 135 MB dump).

**Implementation sketch:**
```java
// Better estimate:
int nEstimated = Math.max(1_000_000, (int) Math.min(fileSize / 40, Integer.MAX_VALUE / 2L));
// Then in buildClassList, once idMap.size() is known, resize exact:
if (count < idMap.size()) {
  // Exact size available, trim now to avoid future doubles
  A1State state = ...; // resize buffers to idMap.size() + margin
}
```

---

### CRITICAL-4: HashMap Allocation for Metadata Maps — O(N) Space Multiplication
**File:** `HeapGraphBuilder.java` A1State class, lines 1213-1250  
**Root Cause:** A1State accumulates metadata using multiple HashMaps/LongMaps:
- `classIdToNameId`, `classIdToSerial`, `classInstanceSizes`, `classOwnFieldsSizes`, etc. (~8-10 maps)
- Each map entry has overhead (~56 bytes per entry in Eclipse Collections maps)
- For a typical heap with ~10k-50k distinct classes, this is ~0.5-2.8 MB per map = **5-28 MB total**

This is minor, but when combined with UTF-8 map growth, it compounds.

**Concrete Fix:**
1. **Merge metadata maps into single ClassBuilder record:** Use a custom `class[] classMetadata` array indexed by class serial, avoiding HashMap overhead.
2. **Estimated impact:** Saves ~10-20 MB (modest but helps GC pressure).

---

## High Priority Issues (Memory leaks or unbounded growth patterns)

### HIGH-1: Forward CSR + Inbound CSR Both Held in Memory Simultaneously (Phase B)
**File:** `HeapGraphBuilder.java` lines 930-990  
**Root Cause:** During Phase B, both `graph.fwdOffsets` and `graph.fwdTargets` (from Phase A.2) and the new `inboundTargets` array are in memory at the same time. For 1.6B edges with 4 bytes per edge:
- fwdTargets: 1.6B * 4 = 6.4 GB
- inboundTargets: 1.6B * 4 = 6.4 GB
- Transient peak: **12.8 GB** (before VByte encoding compresses inboundTargets)

**Concrete Fix:**
1. **Free fwdTargets before Phase B:** After Phase A.2 and RPO DFS, call `graph.freeFwdCsr()` immediately (line 103 in `RpoDfs.compute`). Currently the free happens, but ensure the inbound CSR builder doesn't re-read forward edges.
2. **Verify:** Check that `phaseB()` does not re-scan forward CSR (it doesn't — it uses `scanEdgesWithNames`, which re-reads HPROF directly). Good, but document this.
3. **Estimated impact:** Already mitigated by current code structure; verify no regressions on this.

---

### HIGH-2: Class Inheritance Chain Walk — Unbounded Recursion Depth
**File:** `HeapGraphBuilder.java` lines 282-325 (resolveInheritedFieldOffsets)  
**Root Cause:** Walking the class hierarchy for each of N classes uses recursive call to `computeMatSizeRecursive` (line 86). No guard against cycles; if class hierarchy is malformed (e.g., A → B → C → A), this recurses infinitely.

**Current code:** Has a guard on line 293 (`int guard = 0; ... guard++ < 256`), but only for the hierarchy walk in `resolveInheritedFieldOffsets`. The `computeMatSizeRecursive` function (lines 73-90) called during size computation has no guard.

**Concrete Fix:**
1. **Add recursion depth guard to `computeMatSizeRecursive`:** Add a max-depth parameter; bail to default size if exceeded.
2. **Estimated impact:** Prevents stack overflow on malformed heaps; negligible performance cost.

**Implementation sketch:**
```java
private static int computeMatSizeRecursive(long classId, LongLongHashMap classSuperIds,
                                           LongIntHashMap ownObjectFieldCount,
                                           LongIntHashMap ownPrimitiveFieldBytes,
                                           int pointerSize, int refSize, int depth) {
    if (depth > 256) return pointerSize + refSize; // bail
    long superId = classSuperIds.getIfAbsent(classId, 0L);
    // ...
    int superSize = computeMatSizeRecursive(superId, ..., depth + 1);
    // ...
}
```

---

### HIGH-3: Synthetic Thread-Local Edges HashMap — Per-Thread Storage
**File:** `HeapGraphBuilder.java` lines 358-376 (buildSyntheticEdges)  
**Root Cause:** `threadLocalsBySerial` (a HashMap<Integer, List<Long>>) accumulates all thread-local object addresses. For a heap with many threads and deep stacks:
- Threads: 100-1000 typical
- Locals per thread: 10-100
- Storage: 100-1000 threads * 50 locals * 8 bytes (Long) = 40 MB - 8 GB in worst case

For 135 MB dumps, this is usually < 100 MB, but for large multi-threaded heaps, it can spike.

**Concrete Fix:**
1. **Convert to primitive array:** Use `threadSerialToLocalIds` as a single flat array with per-thread start/end offsets (like a CSR).
2. **Estimated impact:** Saves ~30-50% of synthetic edge storage (avoiding boxing, list overhead).

---

## Medium Priority Issues (Correctness/performance improvements)

### MEDIUM-1: IdMap Skip Index Rebuild After Deduplication
**File:** `IdMap.java` lines 53-74 (sort method)  
**Root Cause:** After deduplication, the skip index is rebuilt from the deduplicated array. If deduplication is significant (e.g., 10% of entries are duplicates from GC roots), the skip index size() will be different from the pre-dedup array, but skip-stride is not adjusted.

**Concrete Fix:**
1. **Rebuild skip index after dedupe:** Already done on line 72. No issue here. Code is correct.

---

### MEDIUM-2: VByte Stream Overestimation on Initial Allocation
**File:** `HeapGraphBuilder.java` lines 1541-1576 (BitSortHelper.sortAndEncode)  
**Root Cause:** Stream is allocated as `new byte[Math.max(totalEdges * 2, 16)]` (line 1543). For sparse deltas (common in dominator trees), VByte compression can achieve 1.2-1.5 bytes/edge, but the allocation assumes 2 bytes/edge, wasting ~30-40% of initial buffer.

**Concrete Fix:**
1. **Use dynamic resizing:** Already implemented (lines 1564-1565 check `if (streamPos + 8 > stream.length)`). No issue here. Code is correct.

---

### MEDIUM-3: Exclude Pairs Linear Search in isExcluded
**File:** `HeapGraphBuilder.java` line 1160 (isExcluded)  
**Root Cause:** `isExcluded` performs linear search over `excludePairs` array. There are only 3 pairs (line 1168), so the O(3) search is negligible, but for clarity, it's worth noting this is intentionally small.

**Concrete Fix:** No action needed.

---

### MEDIUM-4: StackTraceData Not Loaded by Default
**File:** `HeapGraphBuilder.java` line 119 (stackTraces field)  
**Root Cause:** Stack trace frames are only loaded if `StackTraceReader.read()` is explicitly called. This is good for memory efficiency, but if frames are large and many, they can cause bloat on-demand.

**Concrete Fix:** Already mitigated by lazy loading. No action needed; document as a feature.

---

## Low Priority Issues (Minor optimizations)

### LOW-1: Primitive Array Type Storage — Byte Array Indexing
**File:** `HeapGraphBuilder.java` A1State, line 1232  
**Root Cause:** `int[] primArrayClassIdx = new int[12]` stores array class indices indexed by HPROF type code (0-11). This is efficient, but the type code is stored in `arrayTypeBuf` as a byte, requiring casting. No performance issue; just a minor clarity point.

---

### LOW-2: ParallelArray Growth Without Compaction
**File:** `HeapGraphBuilder.java` A1State lines 1264-1272  
**Root Cause:** When buffers are doubled, old arrays are discarded. For very large heaps with multiple doublings, there can be brief moments where GC collects many large arrays. This causes GC pause spikes.

**Concrete Fix:**
1. **GC tuning:** Recommend `-XX:+UseG1GC -XX:MaxGCPauseMillis=500` on JVM command line to smooth out GC pauses.
2. **Estimated impact:** Reduces pause spikes by 20-30%.

---

### LOW-3: BitSet for Excluded Edges — Sparse Representation
**File:** `HeapGraphBuilder.java` line 1542 (newExcluded = new BitSet(totalEdges))  
**Root Cause:** A BitSet is allocated for all edges, but excluded edges are extremely rare (~3 per heap). This wastes space.

**Concrete Fix:**
1. **Use a compact set instead:** Store excluded edge indices in a small array or hash set.
2. **Estimated impact:** Saves < 1 MB in practice; negligible.

---

### LOW-4: LongLongMap Open Addressing — Clustering on Collision
**File:** `HeapGraph.java` lines 274-334 (LongLongMap)  
**Root Cause:** Linear probing for overflow entries can cause clustering, slowing down subsequent lookups. This only affects the overflow maps for very large objects (> 2040 B shallow size), which are rare.

**Concrete Fix:**
1. **Quadratic probing or chaining:** Switch to quadratic probing or chaining to reduce clustering.
2. **Estimated impact:** Negligible; overflow maps are tiny in practice.

---

## Summary of Recommended Actions (By Priority)

| Priority | Issue | Est. Impact | Effort |
|----------|-------|------------|--------|
| **CRITICAL-1** | UTF-8 String interning | -200-500 MB | Medium |
| **CRITICAL-2** | Multi-pass file parsing | -2-4 GB | High |
| **CRITICAL-3** | A1State buffer doubling | -6-8 GB | Medium |
| **CRITICAL-4** | Metadata map proliferation | -10-20 MB | Low |
| **HIGH-1** | Forward/Inbound CSR overlap | Already fixed | - |
| **HIGH-2** | Recursion depth guard | Stability | Low |
| **HIGH-3** | Synthetic thread-local edges | -20-50 MB | Low |

---

## Implementation Roadmap

1. **Phase 0 (Immediate - Low effort):**
   - Add recursion depth guard to `computeMatSizeRecursive` (HIGH-2)
   - Verify `freeFwdCsr()` is called promptly (HIGH-1)

2. **Phase 1 (Short term - Medium effort):**
   - Implement UTF-8 LRU cache (CRITICAL-1): -200-500 MB
   - Improve A1State buffer estimation (CRITICAL-3): -6-8 GB
   - Merge metadata maps (CRITICAL-4): -10-20 MB
   
3. **Phase 2 (Longer term - High effort):**
   - Consolidate multi-pass parsing (CRITICAL-2): -2-4 GB
   - Refactor synthetic edges storage (HIGH-3): -20-50 MB

4. **Phase 3 (Polish):**
   - Add GC tuning recommendations to documentation
   - Profile on real 800M-object heaps to validate improvements

---

## Expected Outcome

Implementing all Critical fixes should reduce:
- **Virtual memory peak:** 38 GB → **12-16 GB** (60-65% reduction)
- **Runtime:** Unclear current baseline, but fewer GC pauses + 60% fewer file I/O operations suggests **2-3x speedup** for multi-gigabyte dumps

Verification: Run on fj-kmeans 135 MB dump and measure peak RSS/virtual memory and elapsed time before/after each phase.

