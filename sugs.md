# RSS Reduction Log — hprof-redact views

Changes committed after baseline `457aae0` ("Add views subcommand") that reduced peak RSS.
Ordered oldest → newest. "N-obj scale" = 513M-object customer dump unless noted.

---

## Committed changes (oldest → newest)

### `76a57e8` — Merge A2b + Phase B into single combined fill scan
- Eliminated a second file pass through the HPROF by combining the forward-CSR fill and
  inbound-CSR fill into one `scanEdgesWithNames` sweep.
- No direct RSS savings (same arrays alive), but enabled future reductions by putting both
  CSRs in memory simultaneously with exact degree counts.
- **Introduced parity regressions** (URLClassLoader/ArrayList retained): `isExcluded()`
  incorrectly returns `true` for `Short.MIN_VALUE` (class-meta edges), causing inbound
  edges to be absent from the CSR. Fix is in working tree (unstaged).

### `b3a733a` — Fast A2a count pass + in-memory inbound CSR build
- Replaced file-scan-to-count with a fast in-memory A2a count pass.
- Moved inbound CSR build fully into memory; inbound stream encoding follows immediately.
- Reduces wall time but minimal direct RSS savings on small heaps.

### `dc6f510` — System.gc() between phases + DominatorTree array reuse + IdMap bucket index
- `System.gc()` after A1, A2, RPO, DOM phases forces G1 to reclaim intermediate structures.
- DominatorTree: moved VByte decode scratch array outside inner loop (was N allocations).
- IdMap bucket index: O(1) bucket lookup instead of binary search.
- **Measured**: VSCode 11M objects: RSS 1440 → 1270 MB (−170 MB, −12%). Wall 12.8 → 10.2s (−20%).

### `2cc0846` — PhaseArrays donation chain + sealed ClassRecord + IdMap bucket fix
- `PhaseArrays`: single-slot int[N] registry passing reusable arrays between pipeline phases.
  Chain: `outDegree → fwdCursor → rpoOrder → depth → retainedSize`.
  Eliminates 3+ fresh N-element allocations across A2/DOM/Retained.
- Sealed `ClassRecord` interface with `Full`/`Slim`/`Array` variants reduces object overhead.

### `2ecca0b` — Free field arrays + isGCRoot after A2, skip VByte stream trim copy
- `ClassRecord.Full` converted to `final class` to allow field-array nulling after A2.
  Frees ~50K `short[]` + `int[]` field arrays (a few hundred MB on large heaps).
- `graph.isGCRoot` BitSet freed after A2 (only needed through end of A2).
- VByte stream: skip trim-copy if `< 1.5×actualSize`; saves up to 1.5×E bytes peak.
  For E=22M VSCode edges: ~33 MB saved.

### `0b89939` — Free inboundStream+inboundOffsets after DOM, gcRootIds after DOM
- `inboundStream` + `inboundOffsets` freed immediately after `DominatorTree.compute()`.
  Stream is 200–600 MB for billion-edge heaps — freeing before RetainedSizes is the largest
  single-step reduction available without algorithmic changes.
- `gcRootIds` + `gcRootTypes` freed after DOM (their last consumer).
- `rpoOrder` donated to `phaseArrays` at end of RetainedSizes.

### `746961c` — A2 two-pass refactor: exact degree counts eliminate in-memory scan
- Added A2a fast count pass so inbound `inDegree` array is exact before fill.
  Eliminated the O(E) in-memory scan of `fwdTargets` that was used to build `inDegree`.
  Also eliminated the compaction loop (fwdTargets previously had gaps from null refs).
- **Measured**: VSCode SerialGC: 1597 → 1389 MB (−208 MB). G1: ~1303 vs ~1247 MB baseline.

### `67132d4` — Eliminate per-record allocations + tighten phase array reuse
- `stringReadBuf`: reuse `byte[]` across UTF-8 string records (eliminates 1–5M byte[] allocations in A1).
- Tighter donation in `phaseArrays`: additional slots contributed.

### `9bd3082` — Eliminate per-class allocations in A1 field collection
- Replaced ~50K `long[2]` allocations (one per object-type field per class) in A1 with
  a flat reuse buffer.
- `RetainedSizes`: donate `childDeg` and `cursor` to `phaseArrays` before nulling.

### `50905d7` — Release LT arrays mid-algorithm + reuse N-slot in report writers
- Lengauer-Tarjan: release 5 of the 7 large arrays mid-algorithm (after each phase) rather
  than at method return, freeing ~5×43 MB before the idom translation pass.
- `buildTopConsumers`: replaced per-suspect `new BitSet(N)` with a single reused BitSet.

### `f59de58` — Eliminate redundant long[] copy in IdMap.sort + trim VByte stream
- `IdMap.sort()`: eliminate temporary `long[N]` copy (~88 MB peak for 11M-object heaps)
  before dedup `copyOf`.
- VByte delta-encoding: trim inbound stream to exact size (saves ~8–16 MB peak).

### `4ae6d30` — Donate inDegree/ibCursor/childOff to phaseArrays
- A2: donate `inDegree[N]` immediately after copying to `inboundOffsets`; available ~43 MB
  earlier for the next taker.
- Donate `fwdCursor[N]` and `ibCursor[N]` after A2b fill completes.
- RetainedSizes: donate `childOff[N+1]` after DFS loop.

### `d96d771` — Fix LongObjectHashMap NPE + takeRaw() + tighten VByte stream sizing
- `takeRaw()` on `PhaseArrays`: take without zeroing (caller is responsible).
- VByte stream buffer: skip realloc if >50% of buffer is unused (avoids ~1.6 GB `copyOf`
  on large heaps).

### `4eba2f2` — tar.gz input + --optimal-gc advisory
- Added `--optimal-gc` advisory output recommending JVM flags.
- Flags: `G1PeriodicGCInterval=50, SoftRefLRUPolicyMSPerMB=500` give ~460 MB reduction on
  11M-object heaps at essentially zero performance cost.

### `8242608` — Semi-NCA dominator algorithm + inboundOffsets int[] + postOrder GC fix
Three independent reductions, ~−8 GB combined on 513M-object heaps:

1. **Semi-NCA replaces Lengauer-Tarjan**: Semi-NCA's NCA forward-pass eliminates `bucket[]`
   and `next[]` arrays from LT. Saves ~4 GB at DOM peak.
2. **`inboundOffsets` int[] (was long[])**: Halves the offset array from ~3.9 GB → ~1.96 GB.
   VByte stream length ≤ 2.5 GB fits in uint32; `Integer.toUnsignedLong()` at read sites.
3. **Drop postOrder donation**: `postOrder[N]` was donated to `phaseArrays` but then kept
   live through the entire LT main loop. Removing the donation lets GC collect it.
   Saves ~2 GB.

### `af9cee7` — N1+N2+N3+N5 array reuse and early-free optimizations (−5.9 GB)
Four improvements documented in `suggestions.md`, now implemented:

- **N1 rpoOrder-reuse** (−1.96 GB): `RpoDfs`: take phaseArrays slot for `rpoOrder`
  instead of `new int[N]`. Reuses the `fwdCursor` array donated by A2.
- **N2 postOrder-elimination** (−1.96 GB): `RpoDfs`: eliminated `postOrder[]` entirely;
  fill `rpoOrder[]` from the back using a reverse-index counter.
- **N3 bucket-early-free** (−0.245 GB): precompute `classNodeIdx[]` at end of A2, then
  call `IdMap.freeBucket()`. Report writers use `classNodeIdx` directly.
- **N5 childTargets-early-null** (−1.96 GB): `RetainedSizes`: null `childTargets` immediately
  after the DFS loop (was held until method return, a ~2 GB unnecessary hold).

**Combined measured**: ~5.88 GB reduction on 513M-object customer dump.

---

## Summary table

| Commit | Change | Approx RSS savings (513M-obj scale) |
|---|---|---|
| `dc6f510` | System.gc() between phases | ~170 MB (measured 11M-obj) |
| `2ecca0b` | Free field arrays after A2 + skip VByte trim copy | ~33 MB + field arrays |
| `0b89939` | Free inboundStream after DOM | ~2–4 GB |
| `746961c` | Exact degree counts (no fwdTargets scan) | ~208 MB (measured 11M-obj) |
| `50905d7` | Release LT arrays mid-algorithm | ~215 MB |
| `f59de58` | Eliminate long[] copy in IdMap.sort | ~88 MB |
| `4ae6d30` | Donate inDegree/ibCursor/childOff | ~43 MB earlier release |
| `d96d771` | Skip VByte realloc on partial buffer | ~1.6 GB (large heaps) |
| `4eba2f2` | --optimal-gc flags | ~460 MB (11M-obj, free) |
| `8242608` | Semi-NCA + inboundOffsets int[] + postOrder fix | ~8 GB (513M-obj) |
| `af9cee7` | N1+N2+N3+N5 (rpoOrder/postOrder/bucket/childTargets) | ~5.88 GB (513M-obj) |
| **Total** | | **~18+ GB** |

---

## Remaining gap to ≤ 20 GB target

See `suggestions.md` for the full plan. After current HEAD (~28.5 GB → ~22.6 GB after
N1-N5), the main remaining opportunity is the Cooper-Harvey-Kennedy (CHK) iterative
dominator algorithm which eliminates `dfsOrder`, `dfsParent`, `sdom`, `label`, `ancestor`
from DOM — roughly −6 GB. See `suggestions.md` section "Advanced Algorithm Research".
