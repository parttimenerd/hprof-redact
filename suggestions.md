# hprof-redact views: RSS Reduction Suggestions

**Goal**: ≤ 20 GB peak RSS on the 513M-object customer heap dump (current: ~28.5 GB post-optimizations).  
**Constraint**: no mmap.

---

## Measured Progress

| Version | RSS (customer dump, 513M obj) | Wall time | Notes |
|---|---|---|---|
| Default G1 (baseline) | **42.2 GB** | 12:35 | Before any work |
| + Optimal GC flags | **36.2 GB** | 10:27 | G1PeriodicGCInterval=50, etc. |
| + Semi-NCA + inboundOffsets int[] + postOrder GC fix | **~28.5 GB** | ~10 min | Current HEAD (benchmark pending) |
| Target | **≤ 20 GB** | — | Customer commitment |

---

## Phase-by-Phase RSS Breakdown (current HEAD, N=513M)

Measured on the customer dump with optimal GC flags. "Always-live" = shallowSizeDiv8(490 MB) + classIndex(979 MB) + idMap.intBuf(1.96 GB) + idMap.bucket(245 MB) = **3.67 GB**.

| Phase | Peak arrays simultaneously live | Total |
|---|---|---|
| **RPO** ← bottleneck | fwdTargets(6.68) + fwdOffsets(1.96) + rpoOrder(1.96) + dfsPos(1.96) + dfsOrder(1.96) + dfsParent(1.96) + postOrder(1.96) + inbound[offs+stream](4.46) + always-live(3.67) | **28.53 GB** |
| DOM Phase 1 (Semi-NCA) | sdom+idomD+label+ancestor(7.84) + inbound(4.46) + dfsPos+dfsOrder+dfsParent+rpoOrder(7.84) + always-live(3.67) | 23.81 GB |
| DOM Phase 2 | sdom+idomD(3.92) + inbound(4.46) + dfsArrays(7.84) + always-live(3.67) | 19.89 GB |
| RetainedSizes peak | rpoOrder+retainedSize+childDeg+childOff+childTargets+cursor(11.76) + classObjClassIdx(0.98) + always-live(3.67) | 16.41 GB |

**The RPO phase (not DOM) is now the true bottleneck at 28.53 GB.**

---

## Opportunities Ranked

### LOW RISK — implement next

| # | Name | Savings | Phase | How |
|---|---|---|---|---|
| N1 | `rpoOrder-reuse` | **−1.96 GB** | RPO | `RpoDfs.java:35`: change `rpoOrder = new int[N]` → `graph.phaseArrays.takeRaw()`. The donated `fwdCursor` from A2 sits in phaseArrays.slot during System.gc() and is never reclaimed; taking it for rpoOrder avoids a fresh allocation. |
| N2 | `postOrder-elimination` | **−1.96 GB** | RPO | `RpoDfs.java:52,99-102`: eliminate `postOrder[]` entirely. Fill `rpoOrder[]` in reverse: use `int rpoIdx = postCount-1` counting down, writing `rpoOrder[rpoIdx--] = node` at each DFS completion. Since `postCount ≤ N`, preallocate `rpoOrder[N]` and fill from the back. |
| N5 | `childTargets-early-null` | **−1.96 GB** | RetainedSizes | `RetainedSizes.java`: add `childTargets = null;` immediately after the `while (sp > 0)` DFS loop (line ~186). Array is provably dead after the loop; the local reference prevents GC until method return. |
| N3 | `bucket-early-free` | **−0.245 GB** | RPO+ | Precompute `int[] classNodeIdx[classCount]` at end of `phaseA2()` by iterating classList and calling `idMap.indexOf(classId)`. Then add `IdMap.freeBucket()` (nulls only `bucket`, keeps `intBuf`). Call after A2. Update `buildClassObjClassIdx`, `SystemOverviewReport:87`, `HtmlReportData:156,284` to use `classNodeIdx` directly. |

**N1 + N2 + N5 + N3 combined: −6.165 GB → RPO peak ~22.4 GB**

### HIGH RISK — biggest win but needs careful implementation

| # | Name | Savings | Phase | How |
|---|---|---|---|---|
| N6 | `chk-dominator` | **−13.72 GB** | RPO + DOM | Replace Semi-NCA with Cooper-Harvey-Kennedy iterative dominators (PLDI 2001). Uses only `idom[N]` + `rpoPos[N]` + `rpoOrder[N]` — no `dfsOrder`, `dfsParent`, `postOrder`, `sdom`, `label`, `ancestor`. Eliminates 7 of the large RPO/DOM arrays. RPO simplifies to just computing `rpoOrder` + `dfsPos`. `intersect(b1,b2)` walks partial idom tree comparing `rpoPos` values. Risk: convergence is O(N × depth_of_dominator_tree) — fast for most heaps (2-10 iterations), catastrophic for pathological deep chains. **Must benchmark convergence iteration count on large heaps before shipping.** Add hard cap (e.g. 200 iterations) with Semi-NCA fallback. |

**With N6 alone: RPO peak ~22.65 GB, DOM peak ~14 GB**

### MEDIUM RISK

| # | Name | Savings | Phase | How |
|---|---|---|---|---|
| N4 | `intBuf-post-retained-free` | **−1.96 GB** | Report writing only | After `RetainedSizes.compute()`, collect the ≤100 node indices that will appear in the HTML report (top-20 by retained among virtual-root children, all thread objects). Store their addresses in a sparse `long[] reportAddresses`. Free `idMap.intBuf` before reports run. Report writers use `reportAddresses[idx]` instead of `idMap.addressAt(idx)`. Note: only affects the ~11 GB report-writing phase, which is already below target — low priority. |

---

## Minimum Path to ≤ 20 GB

Start from current RPO peak of **28.53 GB**:

1. **N2** postOrder elimination: −1.96 GB → 26.57 GB
2. **N1** rpoOrder reuses phaseArrays slot: −1.96 GB → 24.61 GB
3. **N3** bucket freed after A2: −0.245 GB → 24.37 GB
4. **N6** CHK algorithm (eliminates dfsOrder + dfsParent from RpoDfs): −3.92 GB → 20.45 GB
5. **Donate fwdOffsets for dfsPos reuse**: donate `fwdOffsets` when `freeFwdCsr()` is called in RpoDfs, then use `phaseArrays.take()` for `dfsPos` instead of `new int[N]`: −1.96 GB → **18.49 GB ✓**

Steps 1-3 are all LOW risk and should be implemented now (expected ~6 GB savings, measurable on the customer dump before tackling CHK).

---

## Advanced Algorithm Research

### Cooper-Harvey-Kennedy (CHK) Iterative Dominators

- **Paper**: Cooper, Harvey, Kennedy. "A Simple, Fast Dominance Algorithm." Software Practice & Experience, 2001.
- **Memory**: `idom[N]` only (uses already-live `rpoOrder` and `dfsPos`/`rpoPos`). **No** semi-dominator arrays, no spanning-tree parent array, no `dfsOrder`.
- **Time**: O(N × iterations). Iterations = depth of dominator tree in practice.
- **JVM heap structure**: Most objects at shallow dominator depth (≤5). Deep chains possible for linked lists, recursive structures. Empirical data for heap graphs at this scale does not exist.
- **Implementation**: ~50 lines. The `intersect(b1, b2)` function walks up the partial `idom` tree using `rpoPos` to compare, identical to Semi-NCA Phase 2 but repeated until convergence.
- **Validation plan**: Print iteration count on several dumps before shipping. Add `--dom-algo=chk|seminca` flag to allow comparison.

### Semi-NCA Memory Reduction (current algorithm)

Current implementation already uses 4 arrays (`sdom`, `idomD`, `label`, `ancestor`). Further reduction possible by noting `label[d] == d` initially and `ancestor[d] == -1` — these could be packed into a single array using sign bit, saving 1 array = 1.96 GB during DOM Phase 1. Medium risk, medium complexity.

### VByte-encoded Forward Edges

Forward edges (`fwdTargets`) are the largest single array at 6.68 GB during RPO. Delta-encoding is **not reliable** for forward edges: average delta ≈ N/out-degree ≈ 171M → encodes to 4 bytes, same as raw int. No savings unless the heap has strong spatial locality. Skip.

### Inbound CSR Compression (already done)

Inbound edges are already VByte delta-encoded (~1.5 bytes/edge), giving the 2.5 GB stream vs 6.68 GB uncompressed. Already optimal.

---

## Discarded Ideas

| Idea | Why discarded |
|---|---|
| Free `idMap.bucket[]` after A2 without precomputing classNodeIdx | `indexOf()` called post-DOM in `buildClassObjClassIdx` and report writers — must precompute first |
| Free `dfsParent[]` earlier in DOM | Phase 2 reads `dfsPar[v]` for every node — no earlier free point |
| Free `idMap.intBuf[]` before DOM | `addressAt()` called by report writers post-RetainedSizes — not on critical path |
| mmap any array | Explicitly prohibited |
| VByte-encode `fwdTargets` | Forward edge deltas are too large (~171M average) for VByte savings |
| ZGC/Shenandoah GC | Both worse than G1 for RSS — ZGC double-maps heap (2× RSS), Shenandoah uses more regions |

---

## Pre-Existing Parity Failures (separate from RSS work)

18 pre-existing failures in `compare_parity.py` on Renaissance benchmark dumps:

1. **`top_objects/top5_class_overlap`** (9 dumps): we return one fewer entry in "Biggest Objects" than MAT. Fix: increase our top-objects emission limit.
2. **`URLClassLoader`/`ArrayList` retained/instances** (Spark dumps 5,6,7,8): Spark loads classes via multiple classloaders; we deduplicate by class name incorrectly, causing instances under duplicate classloader entries to be lost. Fix: when `buildClassList` encounters a duplicate class name, map the second classId to the existing classList entry.
3. **`Object[]` retained** (Spark dumps): likely same classloader issue or retained-size aggregation across duplicated class entries.

These are being fixed separately.
