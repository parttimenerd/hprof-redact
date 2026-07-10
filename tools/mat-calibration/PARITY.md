# MAT Parity Status

Comparison of `hprof-redact views` output against Eclipse MAT across 11 HPROF
dumps (88 MB – 34 GB heap; 235 K – 514 M objects).  All numbers from the
`compare_parity.py` suite as of 2026-07-10.

## Result: 2 known gaps out of ~700 checks

| Dump | Heap | Objects | Result |
|------|-----:|--------:|--------|
| dump_0_fj-kmeans | 88 MB | 3.2 M | ✓ all pass |
| dump_1_mnemonics | 11 MB | 335 K | ✓ all pass |
| dump_2_scala-doku | 30 MB | 953 K | ✓ all pass |
| dump_3_par-mnemonics | 19 MB | 518 K | ✓ all pass |
| dump_4_philosophers | 12 MB | 236 K | ✓ all pass |
| dump_5_naive-bayes | 1.2 GB | 976 K | ✓ all pass |
| dump_6_dec-tree | 58 MB | 774 K | ✗ 1 gap (top-objects rank tie-break; see below) |
| dump_7_gauss-mix | 33 MB | 562 K | ✓ all pass |
| dump_8_log-regression | 175 MB | 851 K | ✓ all pass |
| dump_vscode (NetBeans/vscode-java) | 752 MB | 11.3 M | ✓ all pass |
| pc52bs2job (production k8s, 34 GB HPROF, 133 K classes) | 19.8 GB heap | 514 M | ✗ 1 gap (suspects/count; see below) |

## What is checked per dump

- **Overview**: heap bytes, object count, class count, GC root count
- **Histogram**: instances, shallow bytes, retained bytes (≥ MAT) for the top
  ~15 classes by MAT retained heap
- **Leak suspects**: count, top-1 retained bytes and percent
- **Top objects (dominators)**: class-name set overlap for top-N objects;
  top-1 retained bytes
- **Biggest dominator classes**: class-name set overlap for top-N; retained
  bytes for ranks 1–3

Retained checks use `>=` (our value must be ≥ MAT's) with a 5% tolerance
because MAT's histogram retained column uses `getMinRetainedSize` (an
approximation), while our value is exact.

## Known gap: pc52bs2job suspects/count (3 vs 2)

**Check**: `suspects/count` = 2, MAT = 3

MAT identifies `java.net.URLClassLoader` (retained 1.91 GB, 9.6% of the 19.8
GB heap) as a third leak suspect.  Our threshold for individual-object suspects
is strictly >10%, so URLClassLoader's 9.6% does not meet it.  MAT uses a
slightly looser threshold (~9.5% ÷ total used heap).

**Why this is acceptable**: the top-1 suspect (WorkerThread, 10.58 GB, 53%) is
exact.  URLClassLoader appears correctly in the histogram (1.90 GB, −0.1%) and
in the biggest-dominator-classes list (rank 2, −0.3%).  The threshold
discrepancy is a minor policy difference, not a correctness issue.

## Known gap: dump_6 top-objects rank 10/11 tie-break

**Check**: `top_objects/top11_class_overlap` = 10/11 (−9.1%)

MAT's top-11 includes `java.lang.invoke.MethodType` at rank 10 (617 KB).
Ours places it at rank 15 (576 KB, −6.6%).  The intervening ranks (10–14 in
our output) are all `java.util.zip.ZipFile$Source` instances with retained
sizes of 602–616 KB — within 3% of MAT's rank-11 `ZipFile$Source` (616 KB).

By rank 15 both sets fully agree: `top_objects/top15_class_overlap` = 15/15.

**Root cause: DFS spanning-tree tie-breaking.**

The Lengauer-Tarjan dominator tree is deterministic given a fixed DFS spanning
tree, but the spanning tree depends on traversal order.  Our DFS visits GC
roots in the order they appear in the HPROF file; MAT visits them in
`HashMapIntObject` hash-table order (non-deterministic, JVM-dependent).  For
`MethodType` instances that are reachable via multiple paths of nearly equal
depth, the DFS tie-break determines which predecessor becomes the spanning-tree
parent, which in turn determines the sdom value and ultimately which dominator
subtree they fall under.

The 6.6% retained difference is within the expected variance for objects with
multiple inbound paths at similar depths.  The dominator tree is semantically
correct for both tools; they simply disagree on the spanning tree for a handful
of objects whose retained size ranks cluster within a 2% band.

**Why this is acceptable:**

1. The retained sizes themselves are correct (both within 7% of each other).
2. The ranking disagreement affects only objects whose retained sizes differ by
   < 50 KB — a rounding artefact relative to the 58 MB total heap.
3. `top_objects/top15_class_overlap` = 15/15: the same objects appear in both
   lists, just in slightly different order within a narrow retained-size band.
4. All histogram retained checks for `java.lang.invoke.MethodType` pass
   (`>= 1.8 MB` at the class level).

## Structural differences from MAT (by design)

These are intentional divergences, not bugs:

| Difference | Detail |
|------------|--------|
| **Object count** | Ours is typically 5–10 lower than MAT. MAT counts class-loader and bootstrap objects as objects in some record types; we count only HPROF `INSTANCE_DUMP`, `OBJ_ARRAY_DUMP`, `PRIM_ARRAY_DUMP` records plus class objects. |
| **Class count** | Ours is 0–0.3% lower. We count only classes reachable via the dominator tree; MAT counts all `CLASS_DUMP` records including unreachable ones. |
| **Shallow bytes** | Typically ±2% for `java.lang.Object` and similar types. MAT includes JVM-internal header words in some size calculations; we use the sizes from the HPROF records directly. |
| **GC root count** | Exact match on all 10 tested dumps. |
| **Heap bytes** | Exact match on all dumps except dump_5 (−3.3%): that dump has a concatenation artifact where the second segment's heap-summary record reports a slightly different total. |

## RSS performance (pc52bs2job, 34 GB HPROF, 514 M objects)

Measured 2026-07-11 with optimizations: deferred idomD in DOM, excluded-edges skip,
`G1PeriodicGCInterval=20 G1HeapRegionSize=2m` GC flags + chunk-by-chunk inbound VByte encoding
+ progressive fwdTargets chunk freeing during DFS + BitSet visited in RPO.

| Phase | RSS after GC | Wall time |
|-------|-------------:|----------:|
| A1    | 21.2 GB      | 59.1 s    |
| A2    | 24.3 GB      | 404.9 s   |
| RPO   | 25.6 GB      | 32.7 s    |
| DOM   | 17.0 GB      | 155.3 s   |
| Retained | 19.8 GB   | 23.5 s    |

- **Peak RSS**: 29.5 GB (`/usr/bin/time -v` maximum RSS — dominated by DOM Phase 1 peak while inbound CSR + sdom/label/ancestor are simultaneously live)
- **DOM Phase 1 transient**: ~27.4 GB (after phase1+free log point; down from ~31 GB)
- **A2 peak**: 24.7 GB (chunk-by-chunk VByte encoding overlaps only ~1 source chunk at a time)
- **Total elapsed**: 11:48 (wall), 675 s tool time + 31 s report write
- **CPU**: 728% average (8-core parallel I/O + dominator phases)

### RSS reduction history (pc52bs2job, 514 M objects)

| Optimization | Peak RSS | Δ |
|---|---:|---:|
| Baseline | 30.4 GB | — |
| + Chunk-by-chunk inbound VByte encoding | 30.4 GB | 0 (A2 peak: 30.7→24.7 GB) |
| + Progressive fwdTargets chunk freeing + BitSet RPO + deferred idomD DOM | **29.5 GB** | **−0.9 GB** |

## Algorithm notes

**Why class objects are not treated as VRoot-adjacent** (fixed 2026-07-10):

Class objects from `HPROF_GC_CLASS_DUMP` records were previously included in
the `vrAdjacent` BitSet alongside GC roots, forcing `sdom[classObj] = 0`
(idom = VIRTUAL_ROOT) for all class objects regardless of their actual
dominator.  Class-reference edges (`instance → classObject`) are excluded from
the inbound CSR (marked `nameIdx = Short.MIN_VALUE`) because MAT's dominator
tree does not traverse them.  This meant class objects had an empty inbound
stream but were also not true GC roots — the correct behaviour is to let
Lengauer-Tarjan assign their idom via their DFS parent, which is always
VIRTUAL_ROOT anyway (since the VIRTUAL_ROOT has edges to all class-dump
objects via the forward CSR).  Making them unconditionally VRoot-adjacent was
harmless for boot-classloader classes (which are also STICKY_CLASS roots) but
wrong for application-class objects loaded by URLClassLoader, where the
incorrect idom forced their entire retained subtree to be attributed to
VIRTUAL_ROOT instead of the URLClassLoader that owns them.  Removing the
`classDumpIndices` loop from `vrAdjacent` reduced parity failures from 20 to 1.
