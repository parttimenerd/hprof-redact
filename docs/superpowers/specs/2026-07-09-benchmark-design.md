# Benchmark Mode for compare_parity.py

**Goal:** Add `--benchmark` mode to `compare_parity.py` that measures RSS and wall-clock runtime for both hprof-tools and MAT, prints a side-by-side table, and validates two goals: `RSS ≤ heapdump_filesize / 2` and `runtime ≤ MAT_runtime / 3`.

**Architecture:** Single new `--benchmark` flag in the existing script. Measurement uses `/usr/bin/time -v` (Linux) to capture peak RSS and wall time for each tool invocation. The benchmark table is printed first; parity checks follow as normal.

**Tech Stack:** Python 3, `/usr/bin/time -v` (GNU time, Linux), existing `ParseHeapDump.sh` MAT batch mode.

---

## Measurement

- **Our tool:** `java -jar hprof-tools.jar views <hprof> /dev/null`
- **MAT:** `ParseHeapDump.sh <hprof> org.eclipse.mat.api:systemoverview`
- Both wrapped with `/usr/bin/time -v` to capture:
  - Wall-clock time (seconds)
  - Peak RSS (KB → MB)
- Each dump measured once (cold start, no warmup — matches real-world use)
- Run sequentially (not parallel) to avoid RSS interference between processes

## New CLI Arguments

- `--benchmark` — enable benchmark mode
- `--mat-sh` — path to `ParseHeapDump.sh` (required when `--benchmark` is set)

## Output Table

Printed before parity checks:

```
Benchmark Results
===================================================================================
 Dump                    File size   Ours RSS   MAT RSS   RSS ratio   Ours t   MAT t   Speedup   Goals
-----------------------------------------------------------------------------------
 dump_2_scala-doku         31 MB       62 MB    310 MB       5.0x      4.2s    14.1s      3.4x   ✓ ✓
 dump_3_spring-petclinic  220 MB      430 MB     ...
-----------------------------------------------------------------------------------
 Goal: RSS ≤ filesize/2 (✓/✗), runtime ≤ MAT/3 (✓/✗)
```

Columns:
- **Dump** — stem name
- **File size** — `.hprof` on-disk bytes (human-readable); mat-calibration dumps are plain uncompressed `.hprof`, so `os.path.getsize()` is correct
- **Ours RSS** — peak RSS of hprof-tools run
- **MAT RSS** — peak RSS of MAT run
- **RSS ratio** — MAT RSS / Ours RSS (higher = we use less memory)
- **Ours t** — wall-clock time for hprof-tools
- **MAT t** — wall-clock time for MAT
- **Speedup** — MAT t / Ours t (higher = we are faster)
- **Goals** — two markers: RSS goal (✓ if Ours RSS ≤ filesize/2) and speed goal (✓ if Speedup ≥ 3×)

## Goal Thresholds

- **RSS goal:** `ours_rss_bytes ≤ hprof_filesize_bytes / 2`
- **Speed goal:** `mat_wall_seconds / ours_wall_seconds ≥ 3.0`

## Error Handling

- If `/usr/bin/time -v` is not available (macOS `time` lacks `-v`), print a clear error and exit.
- If MAT run fails (non-zero exit), mark that row's MAT columns as `ERR` and skip its goal checks.
- If our tool run fails, mark our columns as `ERR`.

## Interaction with Parity

`--benchmark` does not suppress parity output. After the benchmark table, parity checks run as normal. The user gets both in one invocation.

## Files Changed

- `tools/mat-calibration/compare_parity.py` — add `--benchmark`, `--mat-sh` args; `measure_run()` helper; `print_benchmark_table()` function; call both before existing parity logic in `main()`.
