#!/usr/bin/env python3
"""
GC hyperparameter optimizer for hprof-tools views.

Uses Optuna (Bayesian optimization) to find JVM GC flags that minimize peak
RSS across multiple heap dumps while keeping wall time within 20% of baseline.

Supports G1GC (default), ZGC, and Shenandoah.  Region-size and similar
heap-size-aware parameters can be derived from dump file size via simple
linear formulas (e.g. G1HeapRegionSize = clamp(dump_size_mb/100, 1, 32)).

Usage:
  python3 gc_sweep.py [--heap PATH [--heap PATH ...]] [--jar PATH]
                      [--parallelism N] [--output CSV] [--xmx STR]
                      [--trials N] [--max-wall-pct FLOAT]
                      [--starter-flags FLAGS] [--gc {g1,zgc,shenandoah,all}]

Auto-parallelism (when --parallelism is omitted):
    1. Run one baseline trial on the first heap.
    2. Read free memory from /proc/meminfo (Linux) or vm_stat (macOS).
    3. parallelism = max(1, floor(free_memory_kb / 3 / baseline_rss_kb))

Heap-size-aware parameters:
    G1HeapRegionSize is computed from the dump file size:
        region_mb = clamp(dump_size_mb / 100, 1, 32), rounded to power-of-two.
    This formula is included in the search space as a derived feature.

Examples:
  # Default: optimize on the two thinkstation heaps (G1+ZGC+Shenandoah)
  python3 gc_sweep.py --trials 100

  # G1 only, single heap, custom Xmx
  python3 gc_sweep.py --heap /path/to/dump.hprof --gc g1 --xmx 8g --trials 60

  # Provide a known-good starter to warm the optimizer
  python3 gc_sweep.py --starter-flags "-XX:+UseG1GC -XX:G1PeriodicGCInterval=50 ..."

  # Customer dump, less parallelism
  python3 gc_sweep.py --heap /customer.hprof --parallelism 4 --xmx 40g --trials 80
"""

import argparse
import csv
import math
import os
import platform
import re
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Optional

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
DEFAULT_JAR = os.path.expanduser("~/hprof-redact/target/hprof-tools.jar")

THINKSTATION_HEAPS = [
    os.path.expanduser(
        "~/.config/Code/User/workspaceStorage/"
        "16e29a333f9655dc1c45f1d38fb29fa9/Oracle.oracle-java/"
        "userdir/var/log/heapdump.hprof"
    ),
    os.path.expanduser("~/test-heapdumps/dump_2_scala-doku.hprof"),
]

# The known-good G1 config used as baseline for RSS probing and wall-time limit
DEFAULT_BASELINE_FLAGS = (
    "-XX:+UseG1GC -XX:G1PeriodicGCInterval=50 "
    "-XX:+G1PeriodicGCInvokesConcurrent "
    "-XX:MinHeapFreeRatio=1 -XX:MaxHeapFreeRatio=2 "
    "-XX:G1HeapRegionSize=4m -XX:G1PeriodicGCSystemLoadThreshold=0.0 "
    "-XX:SoftRefLRUPolicyMSPerMB=500"
)

DEFAULT_XMX = "6g"
DEFAULT_TRIALS = 100
DEFAULT_MAX_WALL_PCT = 20.0   # reject configs that run >20% slower than baseline
TRIAL_TIMEOUT = 600           # seconds per single trial

# ---------------------------------------------------------------------------
# Optuna import
# ---------------------------------------------------------------------------
try:
    import optuna
    optuna.logging.set_verbosity(optuna.logging.WARNING)
    _HAS_OPTUNA = True
except ImportError:
    _HAS_OPTUNA = False


# ---------------------------------------------------------------------------
# Helpers: free memory, heap file size
# ---------------------------------------------------------------------------
def free_memory_kb() -> int:
    """Return available (free + reclaimable) memory in kilobytes."""
    system = platform.system()
    try:
        if system == "Linux":
            with open("/proc/meminfo") as f:
                text = f.read()
            m = re.search(r"MemAvailable:\s+(\d+)\s+kB", text)
            if m:
                return int(m.group(1))
            m = re.search(r"MemFree:\s+(\d+)\s+kB", text)
            if m:
                return int(m.group(1))
        elif system == "Darwin":
            out = subprocess.check_output(["vm_stat"], text=True)
            page_size = 4096
            ps_m = re.search(r"page size of (\d+) bytes", out)
            if ps_m:
                page_size = int(ps_m.group(1))
            free_pages = 0
            for pat in [r"Pages free:\s+(\d+)", r"Pages inactive:\s+(\d+)",
                        r"Pages speculative:\s+(\d+)"]:
                m2 = re.search(pat, out)
                if m2:
                    free_pages += int(m2.group(1))
            return free_pages * page_size // 1024
    except Exception:
        pass
    return 8 * 1024 * 1024  # fallback: 8 GB


def dump_size_mb(heap_path: str) -> float:
    """Return the size of the heap dump file in megabytes."""
    try:
        return Path(heap_path).stat().st_size / (1024 * 1024)
    except Exception:
        return 1000.0  # fallback


def nearest_power_of_two(x: int) -> int:
    """Return largest power of 2 <= x, clamped to [1, 32]."""
    x = max(1, min(32, x))
    return 2 ** int(math.log2(x))


def g1_region_size_mb(heap_path: str) -> int:
    """Derive G1HeapRegionSize in MB from dump file size: clamp(dump_mb/100, 1, 32)."""
    raw = dump_size_mb(heap_path) / 100.0
    raw_int = max(1, min(32, int(raw)))
    return nearest_power_of_two(raw_int)


# ---------------------------------------------------------------------------
# Parsing helpers
# ---------------------------------------------------------------------------
_RSS_RE = re.compile(r"Maximum resident set size \(kbytes\):\s*(\d+)")
_WALL_RE = re.compile(
    r"Elapsed \(wall clock\) time \(h:mm:ss or m:ss\):\s+"
    r"(\d+(?::\d{2}){1,2}(?:\.\d+)?)"
)


def _parse_rss(output: str) -> Optional[int]:
    m = _RSS_RE.search(output)
    return int(m.group(1)) if m else None


def _parse_wall(output: str) -> Optional[float]:
    m = _WALL_RE.search(output)
    if not m:
        return None
    s = m.group(1)
    parts = s.split(":")
    try:
        if len(parts) == 2:
            return float(parts[0]) * 60 + float(parts[1])
        elif len(parts) == 3:
            return float(parts[0]) * 3600 + float(parts[1]) * 60 + float(parts[2])
    except ValueError:
        pass
    return None


# ---------------------------------------------------------------------------
# Trial runner
# ---------------------------------------------------------------------------
_trial_id_counter = 0


def _next_id() -> int:
    global _trial_id_counter
    tid = _trial_id_counter
    _trial_id_counter += 1
    return tid


def run_trial(
    flags: str,
    heap_path: str,
    xmx: str,
    jar: str,
    trial_id: Optional[int] = None,
) -> dict:
    """Run one JVM trial; return dict with rss_kb, wall_s, exit_code, output."""
    if trial_id is None:
        trial_id = _next_id()
    out_path = f"/tmp/gc_trial_{trial_id}.md"
    cmd = (
        f"/usr/bin/time -v java {flags} -Xmx{xmx} "
        f"-jar {jar} views {heap_path} {out_path} 2>&1"
    )
    try:
        proc = subprocess.run(
            cmd, shell=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, timeout=TRIAL_TIMEOUT,
        )
        output = proc.stdout or ""
        return {
            "trial_id": trial_id,
            "flags": flags,
            "heap_path": heap_path,
            "heap_name": Path(heap_path).name,
            "rss_kb": _parse_rss(output),
            "wall_s": _parse_wall(output),
            "exit_code": proc.returncode,
        }
    except subprocess.TimeoutExpired:
        return {
            "trial_id": trial_id, "flags": flags,
            "heap_path": heap_path, "heap_name": Path(heap_path).name,
            "rss_kb": None, "wall_s": None, "exit_code": -1,
        }
    except Exception:
        return {
            "trial_id": trial_id, "flags": flags,
            "heap_path": heap_path, "heap_name": Path(heap_path).name,
            "rss_kb": None, "wall_s": None, "exit_code": -2,
        }


# ---------------------------------------------------------------------------
# Baseline probing
# ---------------------------------------------------------------------------
def probe_baseline(
    heaps: list[str], jar: str, xmx: str, baseline_flags: str
) -> dict[str, dict]:
    """
    Run baseline on each heap; return map heap_path -> {rss_kb, wall_s}.
    Uses the first heap to compute auto-parallelism.
    """
    results = {}
    for heap in heaps:
        print(f"Probing baseline on {Path(heap).name} ...", flush=True)
        r = run_trial(baseline_flags, heap, xmx, jar, trial_id=_next_id())
        rss = r["rss_kb"]
        wall = r["wall_s"]
        if rss:
            print(f"  Baseline RSS: {rss / 1024:.0f} MB  wall: {wall:.1f}s", flush=True)
        else:
            print(f"  Baseline probe FAILED (exit {r['exit_code']})", flush=True)
        results[heap] = {"rss_kb": rss, "wall_s": wall}
    return results


def compute_parallelism(baseline_rss_kb: Optional[int]) -> int:
    """floor(free_mem / 3 / rss), min 1."""
    free_kb = free_memory_kb()
    print(f"  Free memory: {free_kb / 1024:.0f} MB", flush=True)
    if baseline_rss_kb and baseline_rss_kb > 0:
        p = max(1, int(free_kb / 3 / baseline_rss_kb))
    else:
        p = 4
    print(f"  Auto parallelism: {p}", flush=True)
    return p


# ---------------------------------------------------------------------------
# GC search space definitions
# ---------------------------------------------------------------------------
def suggest_g1_flags(trial, heap_path: str) -> str:
    """Suggest G1GC flags from an Optuna trial.

    Parameters explored:
      G1PeriodicGCInterval          — how often G1 runs a background GC (ms)
      MinHeapFreeRatio / MaxHeapFreeRatio — shrink heap aggressively after GC
      GCTimeRatio                   — target split between app-time and GC-time
                                      (lower = more GC time = more shrinking)
      InitiatingHeapOccupancyPercent — start concurrent GC earlier → smaller live set
      -ShrinkHeapInSteps            — shrink to MaxHeapFreeRatio immediately, not gradually
      G1HeapRegionSize              — smaller regions → less bookkeeping overhead
      SoftRefLRUPolicyMSPerMB       — more aggressive soft-ref eviction
      G1PeriodicGCSystemLoadThreshold — 0.0 = always fire periodic GC regardless of load
    """
    interval = trial.suggest_categorical(
        "g1_interval", [10, 20, 50, 100, 200, 500, 1000]
    )
    min_r = trial.suggest_categorical("g1_min_free", [1, 2, 5, 10])
    max_r = trial.suggest_categorical("g1_max_free", [2, 5, 10, 20, 30])
    if max_r <= min_r:
        max_r = min_r + 1

    # GCTimeRatio: lower values give GC more CPU time and trigger more frequent
    # shrinking.  Default is 9 (10% GC / 90% app).  Try aggressive values.
    gc_time_ratio = trial.suggest_categorical("g1_gc_time_ratio", [4, 9, 19, 49, 99])

    # InitiatingHeapOccupancyPercent: start concurrent marking earlier → keeps
    # live set smaller when phases run, lowering peak RSS.  Default is 45%.
    ihop = trial.suggest_categorical("g1_ihop", [15, 25, 35, 45, 55])

    # -ShrinkHeapInSteps=false: collapse unused heap to MaxHeapFreeRatio in one
    # GC cycle instead of gradually, so early phases don't hold excess pages.
    shrink_steps = trial.suggest_categorical("g1_shrink_steps", [True, False])

    # Heap-size-aware region size (derived from dump file size)
    derived_region = g1_region_size_mb(heap_path)
    region_mb = trial.suggest_categorical("g1_region_mb", [1, 2, 4, 8, 16, 32])
    use_derived = trial.suggest_categorical("g1_use_derived_region", [True, False])
    final_region = derived_region if use_derived else region_mb

    soft = trial.suggest_categorical("g1_soft_ref", [0, 50, 200, 500, 1000])
    load_thresh = trial.suggest_categorical("g1_load_thresh", [0.0, 0.5, 1.0])

    shrink_flag = "-XX:-ShrinkHeapInSteps" if not shrink_steps else "-XX:+ShrinkHeapInSteps"
    flags = (
        f"-XX:+UseG1GC "
        f"-XX:G1PeriodicGCInterval={interval} "
        f"-XX:+G1PeriodicGCInvokesConcurrent "
        f"-XX:MinHeapFreeRatio={min_r} "
        f"-XX:MaxHeapFreeRatio={max_r} "
        f"-XX:GCTimeRatio={gc_time_ratio} "
        f"-XX:InitiatingHeapOccupancyPercent={ihop} "
        f"{shrink_flag} "
        f"-XX:G1HeapRegionSize={final_region}m "
        f"-XX:G1PeriodicGCSystemLoadThreshold={load_thresh} "
        f"-XX:SoftRefLRUPolicyMSPerMB={soft}"
    )
    return flags


def suggest_zgc_flags(trial, heap_path: str) -> str:
    """Suggest ZGC flags from an Optuna trial.

    Parameters explored:
      ZUncommitDelay      — how quickly ZGC returns uncommitted pages to OS
      MinHeapFreeRatio / MaxHeapFreeRatio — constrain heap headroom after GC
      GCTimeRatio         — more GC time → more uncommit cycles
      ShrinkHeapInSteps   — immediate vs. gradual shrink
      SoftRefLRUPolicyMSPerMB
    """
    uncommit_delay = trial.suggest_categorical("zgc_uncommit_delay", [0, 1, 5, 15, 60])
    min_r = trial.suggest_categorical("zgc_min_free", [1, 2, 5, 10])
    max_r = trial.suggest_categorical("zgc_max_free", [2, 5, 10, 20, 30])
    if max_r <= min_r:
        max_r = min_r + 1
    gc_time_ratio = trial.suggest_categorical("zgc_gc_time_ratio", [4, 9, 19, 49, 99])
    shrink_steps = trial.suggest_categorical("zgc_shrink_steps", [True, False])
    soft = trial.suggest_categorical("zgc_soft_ref", [0, 50, 200, 500])
    shrink_flag = "-XX:-ShrinkHeapInSteps" if not shrink_steps else "-XX:+ShrinkHeapInSteps"
    flags = (
        f"-XX:+UseZGC "
        f"-XX:ZUncommitDelay={uncommit_delay} "
        f"-XX:MinHeapFreeRatio={min_r} "
        f"-XX:MaxHeapFreeRatio={max_r} "
        f"-XX:GCTimeRatio={gc_time_ratio} "
        f"{shrink_flag} "
        f"-XX:SoftRefLRUPolicyMSPerMB={soft}"
    )
    return flags


def suggest_shenandoah_flags(trial, heap_path: str) -> str:
    """Suggest Shenandoah flags from an Optuna trial.

    Parameters explored:
      ShenandoahGCHeuristics — collection strategy
      MinHeapFreeRatio / MaxHeapFreeRatio — heap shrink aggressiveness
      GCTimeRatio            — fraction of time allowed for GC
      ShrinkHeapInSteps      — immediate vs. gradual shrink
      SoftRefLRUPolicyMSPerMB
    """
    heuristic = trial.suggest_categorical(
        "shen_heuristic", ["adaptive", "static", "compact", "aggressive"]
    )
    min_r = trial.suggest_categorical("shen_min_free", [1, 2, 5, 10])
    max_r = trial.suggest_categorical("shen_max_free", [2, 5, 10, 20, 30])
    if max_r <= min_r:
        max_r = min_r + 1
    gc_time_ratio = trial.suggest_categorical("shen_gc_time_ratio", [4, 9, 19, 49, 99])
    shrink_steps = trial.suggest_categorical("shen_shrink_steps", [True, False])
    soft = trial.suggest_categorical("shen_soft_ref", [0, 50, 200, 500])
    shrink_flag = "-XX:-ShrinkHeapInSteps" if not shrink_steps else "-XX:+ShrinkHeapInSteps"
    flags = (
        f"-XX:+UseShenandoahGC "
        f"-XX:ShenandoahGCHeuristics={heuristic} "
        f"-XX:MinHeapFreeRatio={min_r} "
        f"-XX:MaxHeapFreeRatio={max_r} "
        f"-XX:GCTimeRatio={gc_time_ratio} "
        f"{shrink_flag} "
        f"-XX:SoftRefLRUPolicyMSPerMB={soft}"
    )
    return flags


def suggest_flags(trial, heap_path: str, gc_choices: list[str]) -> str:
    """Pick GC type then suggest flags."""
    gc = trial.suggest_categorical("gc_type", gc_choices)
    if gc == "g1":
        return suggest_g1_flags(trial, heap_path)
    elif gc == "zgc":
        return suggest_zgc_flags(trial, heap_path)
    elif gc == "shenandoah":
        return suggest_shenandoah_flags(trial, heap_path)
    return suggest_g1_flags(trial, heap_path)


# ---------------------------------------------------------------------------
# CSV output
# ---------------------------------------------------------------------------
CSV_FIELDS = [
    "trial_id", "heap_name", "flags", "gc_type",
    "rss_kb", "rss_mb", "wall_s", "wall_pct_over_baseline", "exit_code",
]


def open_csv(path: str):
    f = open(path, "w", newline="")
    w = csv.DictWriter(f, fieldnames=CSV_FIELDS)
    w.writeheader()
    f.flush()
    return f, w


def write_row(csv_writer, r: dict, baseline_wall: Optional[float]) -> None:
    wall_pct = ""
    if r["wall_s"] is not None and baseline_wall:
        wall_pct = f"{(r['wall_s'] / baseline_wall - 1) * 100:.1f}"
    gc_type = "unknown"
    flags = r.get("flags", "")
    if "UseZGC" in flags:
        gc_type = "zgc"
    elif "UseShenandoah" in flags:
        gc_type = "shenandoah"
    elif "UseG1GC" in flags or "UseG1" in flags:
        gc_type = "g1"
    csv_writer.writerow({
        "trial_id": r["trial_id"],
        "heap_name": r["heap_name"],
        "flags": flags,
        "gc_type": gc_type,
        "rss_kb": r["rss_kb"] if r["rss_kb"] is not None else "",
        "rss_mb": f"{r['rss_kb'] / 1024:.1f}" if r["rss_kb"] is not None else "",
        "wall_s": f"{r['wall_s']:.1f}" if r["wall_s"] is not None else "",
        "wall_pct_over_baseline": wall_pct,
        "exit_code": r["exit_code"],
    })


# ---------------------------------------------------------------------------
# Parallel trial runner for a batch of (flags, heap) pairs
# ---------------------------------------------------------------------------
def run_batch(
    specs: list[dict],   # each: {flags, heap_path, xmx, jar, trial_id}
    parallelism: int,
    baseline_wall_by_heap: dict[str, Optional[float]],
    csv_writer,
    csv_file,
    max_wall_pct: float,
) -> list[dict]:
    """Run specs in parallel; return results that pass the wall-time constraint."""
    results = []
    with ThreadPoolExecutor(max_workers=parallelism) as executor:
        futures = {
            executor.submit(
                run_trial,
                s["flags"], s["heap_path"], s["xmx"], s["jar"], s["trial_id"]
            ): s
            for s in specs
        }
        for fut in as_completed(futures):
            r = fut.result()
            baseline_wall = baseline_wall_by_heap.get(r["heap_path"])
            rss_str = f"{r['rss_kb'] / 1024:.0f}" if r["rss_kb"] is not None else "FAIL"
            wall_str = f"{r['wall_s']:.1f}s" if r["wall_s"] is not None else "N/A"

            wall_pct_str = ""
            wall_over = False
            if r["wall_s"] is not None and baseline_wall:
                pct = (r["wall_s"] / baseline_wall - 1) * 100
                wall_pct_str = f" ({pct:+.0f}%)"
                if pct > max_wall_pct:
                    wall_over = True

            status = " [WALL-LIMIT]" if wall_over else (
                "" if r["exit_code"] == 0 else f" [exit={r['exit_code']}]"
            )
            print(
                f"[{r['trial_id']:04d}] {r['heap_name']}: "
                f"{rss_str} MB {wall_str}{wall_pct_str}{status}  "
                f"{r['flags'][:80]}",
                flush=True,
            )
            write_row(csv_writer, r, baseline_wall)
            csv_file.flush()
            if not wall_over:
                results.append(r)
    return results


# ---------------------------------------------------------------------------
# Optuna objective
# ---------------------------------------------------------------------------
def build_objective(
    heaps: list[str],
    xmx: str,
    jar: str,
    parallelism: int,
    baseline_by_heap: dict[str, dict],
    max_wall_pct: float,
    gc_choices: list[str],
    csv_writer,
    csv_file,
):
    """
    Return a callable suitable as an Optuna objective.

    Objective: minimize sum of RSS (kbytes) across all heaps.
    Trials that fail or exceed the wall-time limit are penalised heavily.
    Heap evaluation is parallelised across the thread pool.
    """
    PENALTY = 99_000_000  # ~94 TB — effectively infinite RSS

    def objective(trial):
        # Use the first heap to suggest flags (heap-size-aware params)
        flags = suggest_flags(trial, heaps[0], gc_choices)

        # Build one spec per heap, run all in parallel
        specs = [
            {
                "flags": flags,
                "heap_path": h,
                "xmx": xmx,
                "jar": jar,
                "trial_id": _next_id(),
            }
            for h in heaps
        ]

        trial_results = run_batch(
            specs, parallelism,
            {h: baseline_by_heap[h]["wall_s"] for h in heaps},
            csv_writer, csv_file, max_wall_pct,
        )

        # Accumulate RSS; penalise missing/failed heaps
        heap_rss = {h: PENALTY for h in heaps}
        for r in trial_results:
            if r["rss_kb"] is not None and r["exit_code"] == 0:
                heap_rss[r["heap_path"]] = r["rss_kb"]

        total = sum(heap_rss.values())
        if total >= len(heaps) * PENALTY:
            raise optuna.exceptions.TrialPruned()
        return total

    return objective


# ---------------------------------------------------------------------------
# Starter-flags warm-up
# ---------------------------------------------------------------------------
def enqueue_starter(
    study,
    starter_flags: str,
    heaps: list[str],
    gc_choices: list[str],
) -> None:
    """
    Parse --starter-flags into Optuna parameter values so the optimizer
    starts near a known-good point.  Unknown flags are silently ignored.
    """
    params = {}

    # Detect GC type
    if "UseZGC" in starter_flags:
        gc = "zgc"
    elif "UseShenandoah" in starter_flags:
        gc = "shenandoah"
    else:
        gc = "g1"

    if gc not in gc_choices:
        gc = gc_choices[0]
    params["gc_type"] = gc

    if gc == "g1":
        m = re.search(r"G1PeriodicGCInterval=(\d+)", starter_flags)
        if m:
            params["g1_interval"] = _nearest(int(m.group(1)), [10, 20, 50, 100, 200, 500, 1000])
        m = re.search(r"MinHeapFreeRatio=(\d+)", starter_flags)
        if m:
            params["g1_min_free"] = _nearest(int(m.group(1)), [1, 2, 5, 10])
        m = re.search(r"MaxHeapFreeRatio=(\d+)", starter_flags)
        if m:
            params["g1_max_free"] = _nearest(int(m.group(1)), [2, 5, 10, 20, 30])
        m = re.search(r"GCTimeRatio=(\d+)", starter_flags)
        if m:
            params["g1_gc_time_ratio"] = _nearest(int(m.group(1)), [4, 9, 19, 49, 99])
        m = re.search(r"InitiatingHeapOccupancyPercent=(\d+)", starter_flags)
        if m:
            params["g1_ihop"] = _nearest(int(m.group(1)), [15, 25, 35, 45, 55])
        params["g1_shrink_steps"] = "-XX:-ShrinkHeapInSteps" not in starter_flags
        m = re.search(r"G1HeapRegionSize=(\d+)", starter_flags)
        if m:
            params["g1_region_mb"] = _nearest(int(m.group(1)), [1, 2, 4, 8, 16, 32])
            params["g1_use_derived_region"] = False
        else:
            params["g1_use_derived_region"] = True
        m = re.search(r"SoftRefLRUPolicyMSPerMB=(\d+)", starter_flags)
        if m:
            params["g1_soft_ref"] = _nearest(int(m.group(1)), [0, 50, 200, 500, 1000])
        m = re.search(r"G1PeriodicGCSystemLoadThreshold=([\d.]+)", starter_flags)
        if m:
            params["g1_load_thresh"] = _nearest_float(float(m.group(1)), [0.0, 0.5, 1.0])

    elif gc == "zgc":
        m = re.search(r"ZUncommitDelay=(\d+)", starter_flags)
        if m:
            params["zgc_uncommit_delay"] = _nearest(int(m.group(1)), [0, 1, 5, 15, 60])
        m = re.search(r"MinHeapFreeRatio=(\d+)", starter_flags)
        if m:
            params["zgc_min_free"] = _nearest(int(m.group(1)), [1, 2, 5, 10])
        m = re.search(r"MaxHeapFreeRatio=(\d+)", starter_flags)
        if m:
            params["zgc_max_free"] = _nearest(int(m.group(1)), [2, 5, 10, 20, 30])
        m = re.search(r"GCTimeRatio=(\d+)", starter_flags)
        if m:
            params["zgc_gc_time_ratio"] = _nearest(int(m.group(1)), [4, 9, 19, 49, 99])
        params["zgc_shrink_steps"] = "-XX:-ShrinkHeapInSteps" not in starter_flags
        m = re.search(r"SoftRefLRUPolicyMSPerMB=(\d+)", starter_flags)
        if m:
            params["zgc_soft_ref"] = _nearest(int(m.group(1)), [0, 50, 200, 500])

    elif gc == "shenandoah":
        m = re.search(r"ShenandoahGCHeuristics=(\w+)", starter_flags)
        if m:
            h = m.group(1)
            params["shen_heuristic"] = h if h in ["adaptive", "static", "compact", "aggressive"] else "adaptive"
        m = re.search(r"MinHeapFreeRatio=(\d+)", starter_flags)
        if m:
            params["shen_min_free"] = _nearest(int(m.group(1)), [1, 2, 5, 10])
        m = re.search(r"MaxHeapFreeRatio=(\d+)", starter_flags)
        if m:
            params["shen_max_free"] = _nearest(int(m.group(1)), [2, 5, 10, 20, 30])
        m = re.search(r"GCTimeRatio=(\d+)", starter_flags)
        if m:
            params["shen_gc_time_ratio"] = _nearest(int(m.group(1)), [4, 9, 19, 49, 99])
        params["shen_shrink_steps"] = "-XX:-ShrinkHeapInSteps" not in starter_flags
        m = re.search(r"SoftRefLRUPolicyMSPerMB=(\d+)", starter_flags)
        if m:
            params["shen_soft_ref"] = _nearest(int(m.group(1)), [0, 50, 200, 500])

    try:
        study.enqueue_trial(params)
        print(f"Starter flags enqueued as trial params: {params}", flush=True)
    except Exception as e:
        print(f"Warning: could not enqueue starter trial: {e}", flush=True)


def _nearest(value: int, choices: list) -> int:
    return min(choices, key=lambda x: abs(x - value))


def _nearest_float(value: float, choices: list) -> float:
    return min(choices, key=lambda x: abs(x - value))


# ---------------------------------------------------------------------------
# Fallback: simple grid search when Optuna is not available
# ---------------------------------------------------------------------------
def run_grid_fallback(
    heaps: list[str],
    xmx: str,
    jar: str,
    parallelism: int,
    baseline_by_heap: dict,
    max_wall_pct: float,
    gc_choices: list[str],
    trials: int,
    csv_writer,
    csv_file,
) -> list[dict]:
    """Coarse G1 grid search as fallback when optuna is unavailable."""
    import itertools

    print("\nOptuna not available — falling back to coarse G1 grid search.", flush=True)
    configs = []

    if "g1" in gc_choices:
        fixed = (
            "-XX:+UseG1GC -XX:+G1PeriodicGCInvokesConcurrent "
            "-XX:G1PeriodicGCSystemLoadThreshold=0.0"
        )
        heap_path = heaps[0]
        reg = g1_region_size_mb(heap_path)
        for interval, min_r, max_r, soft in itertools.product(
            [20, 50, 100, 200],
            [1, 2, 5],
            [2, 5, 10, 20],
            [0, 500],
        ):
            if max_r <= min_r:
                continue
            f = (
                f"{fixed} -XX:G1PeriodicGCInterval={interval} "
                f"-XX:MinHeapFreeRatio={min_r} -XX:MaxHeapFreeRatio={max_r} "
                f"-XX:G1HeapRegionSize={reg}m -XX:SoftRefLRUPolicyMSPerMB={soft}"
            )
            configs.append(f)
            if len(configs) >= trials:
                break
        if len(configs) >= trials:
            pass

    all_results = []
    specs = [
        {"flags": f, "heap_path": h, "xmx": xmx, "jar": jar, "trial_id": _next_id()}
        for f in configs
        for h in heaps
    ]
    results = run_batch(
        specs, parallelism,
        {h: baseline_by_heap[h]["wall_s"] for h in heaps},
        csv_writer, csv_file, max_wall_pct,
    )
    all_results.extend(results)
    return all_results


# ---------------------------------------------------------------------------
# Results summary
# ---------------------------------------------------------------------------
def print_summary(results: list[dict], baseline_by_heap: dict, n: int = 15) -> None:
    heaps = sorted({r["heap_name"] for r in results})
    for heap in heaps:
        subset = sorted(
            [r for r in results if r["heap_name"] == heap and r["rss_kb"] is not None],
            key=lambda r: r["rss_kb"],
        )
        bwall = None
        for hp, bd in baseline_by_heap.items():
            if Path(hp).name == heap:
                bwall = bd.get("wall_s")
                break
        print(f"\n--- Top {n} for heap: {heap} ---")
        print(f"{'Rank':>4}  {'RSS MB':>8}  {'Wall s':>7}  {'Wall%':>6}  Flags")
        for rank, r in enumerate(subset[:n], 1):
            wall = f"{r['wall_s']:.1f}" if r["wall_s"] is not None else "N/A"
            pct = ""
            if r["wall_s"] is not None and bwall:
                pct = f"{(r['wall_s'] / bwall - 1) * 100:+.0f}%"
            print(
                f"{rank:>4}  {r['rss_kb'] / 1024:>8.0f}  "
                f"{wall:>7}  {pct:>6}  {r['flags'][:100]}"
            )

    # Generalization ranking: sum RSS across heaps
    num_heaps = len(heaps)
    if num_heaps >= 2:
        label_rss: dict[str, int] = {}
        label_heaps: dict[str, set] = {}
        label_flags: dict[str, str] = {}
        for r in results:
            if r["rss_kb"] is None:
                continue
            key = r["flags"]
            label_rss[key] = label_rss.get(key, 0) + r["rss_kb"]
            label_heaps.setdefault(key, set()).add(r["heap_name"])
            label_flags[key] = key
        full = {k for k, hs in label_heaps.items() if len(hs) >= num_heaps}
        ranked = sorted([(k, label_rss[k]) for k in full], key=lambda x: x[1])
        print(f"\n--- Generalization ranking (sum RSS across {num_heaps} heap(s)) ---")
        for rank, (flags, total_kb) in enumerate(ranked[:n], 1):
            print(f"{rank:>4}  {total_kb / 1024:>10.0f} MB  {flags[:120]}")


# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--heap", dest="heaps", action="append", metavar="PATH",
                   help="Heap dump path (repeat for multiple). Defaults to thinkstation heaps.")
    p.add_argument("--jar", default=DEFAULT_JAR,
                   help=f"Path to hprof-tools.jar (default: {DEFAULT_JAR})")
    p.add_argument("--parallelism", type=int, default=None, metavar="N",
                   help="Thread-pool size. Auto-detected when omitted: free_ram/3/baseline_rss.")
    p.add_argument("--output", default="/tmp/gc_sweep_results.csv", metavar="CSV",
                   help="CSV output path (default: /tmp/gc_sweep_results.csv)")
    p.add_argument("--xmx", default=DEFAULT_XMX, metavar="STR",
                   help=f"JVM -Xmx flag (default: {DEFAULT_XMX})")
    p.add_argument("--trials", type=int, default=DEFAULT_TRIALS,
                   help=f"Number of Optuna trials (default: {DEFAULT_TRIALS})")
    p.add_argument("--max-wall-pct", type=float, default=DEFAULT_MAX_WALL_PCT,
                   help=f"Max allowed wall-time increase over baseline %% (default: {DEFAULT_MAX_WALL_PCT})")
    p.add_argument("--gc", choices=["g1", "zgc", "shenandoah", "all"], default="all",
                   help="GC types to explore (default: all)")
    p.add_argument("--starter-flags", default=None, metavar="FLAGS",
                   help="Known-good JVM flags to warm-start the optimizer.")
    p.add_argument("--baseline-flags", default=DEFAULT_BASELINE_FLAGS, metavar="FLAGS",
                   help="Flags for baseline RSS/wall probing. Default: validated i50_fast config.")
    return p.parse_args()


def resolve_heaps(heaps_arg: Optional[list]) -> list[str]:
    if heaps_arg:
        return heaps_arg
    available = [h for h in THINKSTATION_HEAPS if Path(h).exists()]
    if not available:
        print("WARNING: none of the default heap paths exist. Specify --heap explicitly.",
              file=sys.stderr)
    return available


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main() -> None:
    args = parse_args()

    heaps = resolve_heaps(args.heaps)
    if not heaps:
        sys.exit("No heap dumps available. Exiting.")

    jar = str(Path(args.jar).expanduser())
    if not Path(jar).exists():
        sys.exit(f"JAR not found: {jar}")

    # Copy the jar once so mid-sweep rebuilds don't affect running trials.
    jar_copy = f"/tmp/gc_sweep_hprof_tools_{os.getpid()}.jar"
    shutil.copy2(jar, jar_copy)
    jar = jar_copy
    print(f"JAR (copy)   : {jar}", flush=True)

    gc_choices = ["g1", "zgc", "shenandoah"] if args.gc == "all" else [args.gc]

    print(f"Heaps        : {[Path(h).name for h in heaps]}", flush=True)
    print(f"Xmx          : {args.xmx}", flush=True)
    print(f"Trials       : {args.trials}", flush=True)
    print(f"Max wall +%  : {args.max_wall_pct}%", flush=True)
    print(f"GC choices   : {gc_choices}", flush=True)
    print(f"Optuna       : {'yes' if _HAS_OPTUNA else 'NO — fallback grid search'}", flush=True)

    # Probe baseline on all heaps
    baseline_by_heap = probe_baseline(heaps, jar, args.xmx, args.baseline_flags)

    # Auto-detect parallelism
    if args.parallelism is None:
        first_rss = baseline_by_heap[heaps[0]].get("rss_kb")
        parallelism = compute_parallelism(first_rss)
    else:
        parallelism = args.parallelism
    print(f"Parallelism  : {parallelism}", flush=True)

    csv_file, csv_writer = open_csv(args.output)

    # Write baseline rows to CSV
    for heap in heaps:
        bd = baseline_by_heap[heap]
        row = {
            "trial_id": _next_id(),
            "flags": args.baseline_flags,
            "heap_path": heap,
            "heap_name": Path(heap).name,
            "rss_kb": bd.get("rss_kb"),
            "wall_s": bd.get("wall_s"),
            "exit_code": 0,
        }
        write_row(csv_writer, row, bd.get("wall_s"))
    csv_file.flush()

    all_results = []

    if _HAS_OPTUNA:
        # ------------------------------------------------------------------
        # Optuna-based Bayesian optimization
        # ------------------------------------------------------------------
        sampler = optuna.samplers.TPESampler(seed=42)
        study = optuna.create_study(
            direction="minimize",
            sampler=sampler,
            study_name="gc_rss_opt",
        )

        # Enqueue baseline as first trial
        enqueue_starter(study, args.baseline_flags, heaps, gc_choices)

        # Enqueue user's starter flags if provided
        if args.starter_flags:
            enqueue_starter(study, args.starter_flags, heaps, gc_choices)

        objective = build_objective(
            heaps=heaps,
            xmx=args.xmx,
            jar=jar,
            parallelism=parallelism,
            baseline_by_heap=baseline_by_heap,
            max_wall_pct=args.max_wall_pct,
            gc_choices=gc_choices,
            csv_writer=csv_writer,
            csv_file=csv_file,
        )

        print(f"\n{'='*60}", flush=True)
        print(f"  Running {args.trials} Optuna trials ...", flush=True)
        print(f"{'='*60}", flush=True)

        study.optimize(objective, n_trials=args.trials, show_progress_bar=False)

        print(f"\nBest trial value (sum RSS KB): {study.best_value:,}", flush=True)
        print(f"Best params: {study.best_params}", flush=True)

    else:
        # ------------------------------------------------------------------
        # Grid search fallback
        # ------------------------------------------------------------------
        fallback_results = run_grid_fallback(
            heaps=heaps, xmx=args.xmx, jar=jar,
            parallelism=parallelism,
            baseline_by_heap=baseline_by_heap,
            max_wall_pct=args.max_wall_pct,
            gc_choices=gc_choices,
            trials=args.trials,
            csv_writer=csv_writer,
            csv_file=csv_file,
        )
        all_results.extend(fallback_results)

    # Reload all results from CSV for final summary
    csv_file.flush()
    csv_file.close()

    final_results = []
    with open(args.output, newline="") as f:
        for row in csv.DictReader(f):
            rss_kb = int(row["rss_kb"]) if row.get("rss_kb") else None
            wall_s = float(row["wall_s"]) if row.get("wall_s") else None
            final_results.append({
                "trial_id": row["trial_id"],
                "heap_name": row["heap_name"],
                "flags": row["flags"],
                "rss_kb": rss_kb,
                "wall_s": wall_s,
                "exit_code": int(row["exit_code"]),
            })

    print(f"\n{'='*60}", flush=True)
    print("  FINAL SUMMARY", flush=True)
    print(f"{'='*60}", flush=True)
    print_summary(final_results, baseline_by_heap)
    print(f"\nFull results written to: {args.output}", flush=True)


if __name__ == "__main__":
    main()
