# Performance Analysis: hprof-redact views package

Target workload: 1 million+ objects, 2+ million edges, 100 MB–2 GB HPROF files.

---

## 1. Concrete Performance Bottlenecks

### 1.1 Five file passes instead of three

**File:** `HeapGraphBuilder.java`  
**Summary:** The build pipeline opens the HPROF file five times for sequential reads. Phase A.2 opens it twice (sub-pass A.2a count, sub-pass A.2b fill), then Phase B opens it twice more (one inDegree recount, one named-edge fill). For a 2 GB file on spinning disk or networked storage this is catastrophic; even on NVMe it wastes significant I/O time.

The inDegree recount at the start of `phaseB` (lines 955–966) is entirely redundant: the same counts were computed in sub-pass A.2a and stored in `inDegree`. They are not mutated before Phase B begins—only `fwdOffsets`/`fwdTargets` are written from those counts. The `inDegree` array (`inDegreeCount`) in `phaseA2` is discarded after the prefix-sum at lines 618–623, but the prefix-sum result itself (`inboundOffsets`) could be retained and passed to Phase B.

**Fix:** Thread `inboundOffsets` (or the raw `inDegree` counts) from `phaseA2` through to `phaseB`, eliminating one full file pass. This reduces five sequential reads to four.

### 1.2 `INSTANCE_DUMP` data allocated as `byte[]` twice per object per edge-scan pass

**File:** `HeapGraphBuilder.java`, `scanEdgesInSegment` line 718; `scanEdgesWithNamesInSegment` line 1056  
**Summary:** For every `HPROF_GC_INSTANCE_DUMP` record encountered in sub-pass A.2b and the Phase B named-edge scan, `p.readBytes(dataLen)` allocates a fresh `byte[]`. With one million instances and an average instance data size of ~64 bytes, each of these two passes allocates ~64 MB of short-lived objects. GC pressure compounds with allocation: these arrays are immediately eligible for collection but generate significant minor-GC activity.

**Fix:** Maintain a single reusable `byte[]` scratch buffer in the scan methods, growing it to `max(dataLen)` seen so far:

```java
// field in HeapGraphBuilder:
private byte[] instanceDataBuf = new byte[256];

// in scanEdgesInSegment / scanEdgesWithNamesInSegment:
if (dataLen > instanceDataBuf.length)
    instanceDataBuf = new byte[dataLen];
p.readBytesInto(instanceDataBuf, dataLen);
```

`Parser.readBytesInto` is a straightforward addition that reads directly into the provided buffer without allocation.

### 1.3 `sortWithFlags` is O(n²) for rows with more than 16 entries

**File:** `HeapGraphBuilder.java`, `BitSortHelper.sortWithFlags` lines 1609–1628; `CsrBuilder.java`, `sortWithFlags` lines 276–320  
**Summary:** For rows larger than 16 (i.e., nodes with in-degree > 16), the code strips flags, sorts, then re-applies flags via a linear scan per entry: an O(n²) algorithm. High-degree nodes (e.g., widely-shared utility objects, arrays, or the bootstrap classloader) can easily have thousands of incoming edges. A node with 10,000 predecessors takes ~50 million comparisons.

**Fix:** Pack the flag into the lower bit before sorting, then use standard sort and unpack:

```java
// Pack: encode as (srcIdx << 1) | excludedBit, sort normally, unpack
for (int i = lo; i < hi; i++) {
    int raw = arr[i];
    int src = raw & Integer.MAX_VALUE;
    int flag = (raw >>> 31) & 1;
    arr[i] = (src << 1) | flag;  // requires N < 2^30, true for all real heaps
}
Arrays.sort(arr, lo, hi);
for (int i = lo; i < hi; i++) {
    int v = arr[i];
    arr[i] = (v >>> 1) | ((v & 1) << 31);
}
```

This is O(n log n) and avoids the `boolean[]` and nested scan entirely.

### 1.4 `buildPath` and `writeAccumulationPath` are O(N) per depth step

**File:** `HtmlReportData.java` lines 469–486; `LeakSuspectsReport.java` lines 129–152  
**Summary:** Finding the best child at each depth step scans all N nodes to find `idom[i] == current`. With N = 1 million and a path of depth 5, that is 5 million iterations per suspect. For large heaps with multiple suspects this adds seconds to report generation.

The dominator-tree children CSR is already built inside `RetainedSizes.compute` (lines 80–97) for the DFS. That structure (`childTargets`/`childOff`) is discarded at the end of `RetainedSizes.compute` (local variables go out of scope). If it were retained on the graph, path-walking would become O(degree) per step instead of O(N).

**Fix:** Expose `int[] domChildOffsets` / `int[] domChildTargets` on `HeapGraph`, populate them once in `RetainedSizes.compute` (they are already computed there), and use them in `buildPath` and `writeAccumulationPath`.

### 1.5 `resolveExcludePairs` does O(C) + O(F) scans per pair for name resolution

**File:** `HeapGraphBuilder.java` lines 1182–1213  
**Summary:** The three default exclude pairs are resolved by iterating `graph.classList` (O(C) for class name lookup) and then iterating `graph.fieldNameIntern.entrySet()` (O(F)) for each pair. For a heap with 50,000 classes and 100,000 field names this is ~450,000 iterations. The field-name lookup inverts the intern map by calling `graph.fieldNameFor(e.getValue())` on each entry—a slow reverse lookup.

**Fix:** Build a transient `name→classIdx` map during `buildClassList` (already iterating classList), and look up field names directly via `utf8Strings` before interning rather than scanning the intern map in reverse.

### 1.6 `isExcluded` linear scan on every inbound edge

**File:** `HeapGraphBuilder.java` line 1173; `CsrBuilder.java` line 126  
**Summary:** `isExcluded` iterates `excludePairs` (always length ≤ 3) for every single inbound edge stored. With 2 million edges this is 6 million comparisons. While individually cheap, a two-level lookup keyed on `(classIdx << 16) | nameIdx` packed into a small `int[]` set or even a single precomputed `long` bitmask would reduce it to one operation.

### 1.7 `computeMatSizeRecursive` recomputes intermediate results and has no stack guard

**File:** `HeapGraphBuilder.java` lines 73–90  
**Summary:** `computeMatSizeRecursive` is recursive and walks the full superclass chain without caching intermediate (non-leaf) results. The outer `computeMatInstanceSize` caches only the final `alignUp` result. For class B extending C extending D (each computed cold), computing size(B) recurses into size(C) which recurses into size(D); if later size(C) is requested again it is not in the cache. Also there is no recursion depth guard—the `guard` variable in `resolveInheritedFieldOffsets` (line 295) caps hierarchy walks at 256, but the recursive size computation has no equivalent.

**Fix:** Inline the cache lookup inside `computeMatSizeRecursive` itself so all intermediate results are memoized, and add a depth counter to guard against cycles.

---

## 2. Memory Usage Improvements

### 2.1 `A1State` parallel arrays duplicate addresses already in `IdMap`

**File:** `HeapGraphBuilder.java`, `A1State` lines 1220–1525  
**Summary:** `A1State` maintains `addrBuf` (long[]), `shallowBuf` (int[]), `classIdBuf` (long[]), and `arrayTypeBuf` (byte[]) in parallel, while `IdMap.buf` also holds every address. Before `idMap.sort()` there are two copies of every address: one in `addrBuf` and one in `IdMap.buf`. For 1 million objects at 8 bytes each that is 16 MB of duplicate long arrays.

`appendShallowSize`, `appendClassId`, and `appendArrayType` (lines 1291–1302) all depend on `addrBuf[count-1]` being the just-appended address, so both arrays must be kept in sync. The simplest fix is to eliminate `addrBuf` and drive all metadata writes by `count` directly (since `appendAddress` always increments `count` first), removing the need for the address-equality check entirely.

### 2.2 `classObjFields` uses `HashMap<Long, List<long[]>>` with extensive boxing

**File:** `HeapGraphBuilder.java`, `A1State` line 1243  
**Summary:** `classObjFields` maps each classId (boxed `Long`) to a `List<long[]>` where each entry is a two-element `long[]`. With 50,000 classes and an average of 3 object fields per class, this creates ~150,000 `long[]` allocations plus ~50,000 boxed Longs. Use Eclipse Collections' `LongObjectHashMap<long[]>` with a flat encoding (alternating nameId, offset in the array) to collapse this to ~50,000 allocations with zero boxing.

### 2.3 `fwdOffsets`/`fwdTargets` and `inboundTargets` allocation overlap in Phase A.2/B transition

**File:** `HeapGraphBuilder.java` lines 619–624, lines 970–971  
**Summary:** At the end of Phase A.2, `fwdOffsets[N+1]` and `fwdTargets[totalEdges]` are both live. Phase B then allocates `inDegree[N]` and `inboundTargets[total]`. For 1M nodes and 2M edges this is approximately: fwdOffsets (4 MB) + fwdTargets (8 MB) + inDegree (4 MB) + inboundTargets (8 MB) = 24 MB live simultaneously. The fwdCSR is freed by `RpoDfs.compute` before Phase B, so the actual overlap at Phase B entry is only `inDegree` + `inboundTargets` alongside the already-freed fwdCSR. No change needed here, but note that the redundant inDegree recount in Phase B (§1.1) causes an unnecessary extra 4 MB allocation.

### 2.4 Dominator-tree DFS in `RetainedSizes` allocates eight O(N) arrays simultaneously

**File:** `RetainedSizes.java` lines 80–113  
**Summary:** The `hasSameClassAncestor` DFS allocates: `childDeg[N]`, `childOff[N+1]`, `childTargets[E]`, `cursor[N]`, `classToLastDepth[C+1]`, `classObjDepth[C+1]`, `stackNode[N+1]`, `stackChildIdx[N+1]`, `stackSavedDepth[N+1]`, `stackSavedObjDepth[N+1]`. For N=1M and E=2M this is approximately 4×4 MB (stack arrays) + 8 MB (childTargets) + 4 MB (childOff + childDeg + cursor) ≈ 28 MB peak.

`cursor` (line 91) is used only during the CSR fill loop then set to null (line 98), and `childDeg` (line 80) is set to null (line 100)—good. The four stack arrays can be reduced to three by bit-packing `stackSavedDepth` and `stackSavedObjDepth` into a single `long[]` (each value is a depth ≤ N, fitting in 32 bits).

### 2.5 `HeapGraph.retainedSizeOf` null-checks overflow map on every hot-path call

**File:** `HeapGraph.java` lines 227–233  
**Summary:** In the retained-size accumulation loop (`RetainedSizes.java` lines 41–51), `retainedSizeOf` is called twice per node: once for the child and once for the parent. The current implementation checks `retainedSizeOverflow != null` before the fast-path `Integer.toUnsignedLong(retainedSize[idx])`. The overflow map is non-null only when some object retains more than 4.29 GB—extremely rare. Reordering to check the sentinel value first avoids the null check in the common case:

```java
long retainedSizeOf(int idx) {
    int raw = retainedSize[idx];
    if (raw != (int)0xFFFFFFFFL) return Integer.toUnsignedLong(raw);
    if (retainedSizeOverflow != null) {
        long v = retainedSizeOverflow.get(idx);
        if (v != LongLongMap.NOT_FOUND) return v;
    }
    return 0xFFFFFFFFL;
}
```

---

## 3. Algorithm Improvements

### 3.1 Top-N reports build full sorted lists instead of using a bounded heap

**File:** `HtmlReportData.java` lines 178–195, 200–224, 228–252, 256–280; `TopConsumersReport.java` lines 37–44  
**Summary:** `buildBiggestObjects`, `buildBiggestClasses`, `buildBiggestPackages`, and `buildBiggestClassLoaders` all collect every candidate into an `ArrayList`, then call `.sort()` on the entire list to extract the top 20. With 100,000 top-level dominators, allocating `ArrayList<int[]>` of that size and sorting it is wasteful. Use a `PriorityQueue<Integer>` of capacity 20 (min-heap) and keep only the top 20 during the single O(N) scan, achieving O(N log 20) time with O(20) extra space.

### 3.2 `buildTopConsumers` does two O(N) passes per suspect via BitSet propagation

**File:** `HtmlReportData.java` lines 379–408  
**Summary:** For each suspect node, `buildTopConsumers` allocates a `BitSet(N)`, sets the root, then iterates all nodes 1..N propagating `idom[v]` membership, then iterates the set bits. For N=1M with 10 suspects this is 20M iterations. If the children CSR from `RetainedSizes` were retained on the graph (see §1.4), a DFS over only the subtree (typically far smaller than N) would suffice.

### 3.3 `DominatorTree` convergence guard allows up to N iterations before warning

**File:** `DominatorTree.java` lines 52–57  
**Summary:** The convergence guard `iter > N + 10` permits N+10 full passes before warning. For N=1M that is over a million O(N × avg_in_degree) iterations—effectively an infinite loop in practice. CHK on a correct RPO ordering of a reducible graph converges in 1–2 iterations; for irreducible graphs a bound of ~50 is generous. The guard should be `iter > 50` (or some similarly small constant) to catch pathological inputs immediately.

### 3.4 `IdMap.canUseCompressedOops` iterates all N addresses to check alignment

**File:** `IdMap.java` lines 76–83  
**Summary:** `canUseCompressedOops` scans all N addresses after sorting. Since the array is sorted, alignment can be checked by examining only the first entry (`buf[0]`) for the `(addr & 7) != 0` case, and the last entry (`buf[size-1]`) for the overflow case. This reduces an O(N) scan to O(1):

```java
private boolean canUseCompressedOops() {
    if (size == 0) return false;
    if ((buf[0] & 7L) != 0) return false;          // any non-aligned → no
    if ((buf[size-1] >>> 3) > 0xFFFFFFFFL) return false; // max exceeds 32-bit → no
    return true;
}
```

The assumption is that if all addresses are 8-byte aligned (the norm for JVM heaps), then `buf[0]` being aligned implies all are. This holds because misaligned addresses are exceptional; if any address is misaligned, `buf[0]` has a 7/8 chance of being misaligned given uniform distribution.  
**Note:** This is only safe if every address in the dump shares the same alignment property. The conservative fallback is to check first + last + a sample of N entries. For maximum safety, check only the last entry for the overflow condition (since the array is sorted, `buf[size-1]` is the maximum) and keep the alignment scan but break early on first violation.

### 3.5 `resolveInheritedFieldOffsets` allocates `ArrayList` and `int[]` per class

**File:** `HeapGraphBuilder.java` lines 284–327  
**Summary:** For each of C classes, `resolveInheritedFieldOffsets` allocates two `ArrayList`s (`nameChunks`, `offsetChunks`) and one `int[]` per level of the hierarchy to hold adjusted offsets. With 50,000 classes and average hierarchy depth 4, this is ~200,000 short-lived allocations. Replace the ArrayLists with a single flat `int[]` scratch buffer (reused across classes) that accumulates adjusted offsets directly.

---

## 4. Code Quality / Maintainability Issues

### 4.1 `CsrBuilder.encodeVByte` is broken dead code

**File:** `CsrBuilder.java` lines 150–220  
**Summary:** `CsrBuilder.encodeVByte()` contains an acknowledged bug: the exclude-flag re-indexing logic is incomplete (lines 193–213 include a comment "CORRECTION: use a separate logical edge counter" that was never implemented). This method is superseded by `encodeVByteWithEmbeddedFlags()` and by `BitSortHelper.sortAndEncode` in `HeapGraphBuilder`. The broken method should be removed to avoid accidental use.

### 4.2 `CsrBuilder` and `HeapGraphBuilder.BitSortHelper` duplicate `sortWithFlags`

**File:** `CsrBuilder.java` lines 276–320; `HeapGraphBuilder.java` lines 1594–1628  
**Summary:** Two nearly identical implementations of `sortWithFlags` exist, both containing the O(n²) flaw described in §1.3. One authoritative implementation should live in a shared package-private utility class, and both callers should use it.

### 4.3 `HeapGraph.totalHeapBytes()` is an uncached O(N) scan duplicated in multiple places

**File:** `HeapGraph.java` lines 247–250; `HtmlReportData.java` lines 85–88; `TopConsumersReport.java` line 52–53; `LeakSuspectsReport.java` line 39–42  
**Summary:** `totalHeapBytes()` iterates all N objects on every call. The same loop appears inline in `HtmlReportData.compute`, `TopConsumersReport.writeBiggestObjects`, and `LeakSuspectsReport.write`. The field `heapTotalBytes` already exists on `HeapGraph` (line 43) with the comment "set by builder after all shallow sizes collected"—but it is never set (initialized to 0 at line 142). Populate it once in `HeapGraphBuilder.build()` and replace all callers with `graph.heapTotalBytes`.

### 4.4 `HeapGraphBuilder.skipToHeapSection` is unreachable dead code

**File:** `HeapGraphBuilder.java` lines 1115–1133  
**Summary:** `skipToHeapSection` is a private static method that is never called. Its body contains a comment describing an abandoned design. Remove it.

### 4.5 `HeapGraph.classSerialToIndex` uses boxed `Map<Integer, Integer>`

**File:** `HeapGraph.java` lines 106–107  
**Summary:** `classSerialToIndex` is declared as `Map<Integer, Integer>` with the comment "keep boxed, small". Eclipse Collections' `IntIntHashMap` is a direct drop-in replacement that eliminates boxing. With ~50,000 classes the impact is minor but inconsistent with the rest of the codebase using primitive maps throughout.

### 4.6 `A1State.appendShallowSize` / `appendClassId` / `appendArrayType` repeat redundant address checks

**File:** `HeapGraphBuilder.java` lines 1291–1302  
**Summary:** Each of these three methods checks `count > 0 && addrBuf[count-1] == addr`. Since they are always called immediately after `appendAddress(addr)`, the guard is defensive but adds three array reads and comparisons per object (3 million extra comparisons for 1M objects). Consolidate into a single `appendObject(addr, shallow, classId, arrayType)` method that writes all four slots without the redundant checks.

### 4.7 `VByte.decode` uses a mutable `int[1]` output parameter

**File:** `VByte.java` lines 25–36; `DominatorTree.java` line 92  
**Summary:** The decode API requires callers to pass a `int[1]` wrapper to receive the decoded value. In `DominatorTree.computeNewIdom`, `tmp = new int[1]` is allocated once outside the loop—acceptable. The API could encode both result and new position in a single `long` return value (`(long)value << 32 | newPos`), which avoids the wrapper array and is more amenable to JIT inlining:

```java
static long decodeL(byte[] buf, int pos) {
    int value = 0, shift = 0;
    while (true) {
        int b = buf[pos++] & 0xFF;
        value |= (b & 0x7F) << shift;
        if ((b & 0x80) == 0) return ((long)value << 32) | (pos & 0xFFFFFFFFL);
        shift += 7;
    }
}
// caller: long r = VByte.decodeL(stream, pos); int val = (int)(r >>> 32); pos = (int)r;
```

### 4.8 `DominatorTree.intersect` performs two independent bounded walks in a loop that may not terminate

**File:** `DominatorTree.java` lines 116–143  
**Summary:** The `intersect` method uses two `while (rpoPos[finger] > rpoPos[other])` loops nested inside `while (finger1 != finger2)`. The inner step-count guards (`steps1 > maxSteps`) are correct but the outer loop has no total-step bound. In theory the inner guards collapse both fingers to VIRTUAL_ROOT which makes them equal, terminating the outer loop. In practice this is safe, but an explicit outer step limit makes the invariant clearer and prevents any future regression from making the loop infinite.
