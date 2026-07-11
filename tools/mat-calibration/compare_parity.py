#!/usr/bin/env python3
"""
MAT parity checker for hprof-redact views.

Compares three sections across all heap dumps:
  1. System Overview  (heap summary + class histogram)
  2. Leak Suspects    (number of suspects + top suspect retained)
  3. Top Consumers    (biggest objects, biggest dominator classes)

Usage (run on thinkstation):
  python3 compare_parity.py \\
      --dumps-dir ~/test-heapdumps \\
      --jar    ~/hprof-redact/target/hprof-redact.jar

Caching:
  - MAT zip reports are parsed once and stored as <stem>_mat_cache.json next to the zips.
    Delete the .json file to force re-parse from the zip.
  - Our tool output is stored as <stem>_ours.md.  Pass --rerun to regenerate.

Flags:
  --dump <stem>       Only process this one dump (e.g. dump_2_scala-doku)
  --rerun             Re-run hprof-redact even if _ours.md already exists
  --failures-only     Print only FAIL lines (quieter for CI)
  --no-color          Disable ANSI colours

Exit code: 0 = all checks within tolerance, 1 = regressions found.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import zipfile
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Optional

# ── tolerance ─────────────────────────────────────────────────────────────────
REL_TOL = 0.05            # 5 % relative tolerance
ABS_FLOOR_BYTES = 512 * 1024   # absolute floor to avoid noise on tiny values

ANSI_RED   = "\033[91m"
ANSI_GREEN = "\033[92m"
ANSI_RESET = "\033[0m"
ANSI_BOLD  = "\033[1m"

# ── HTML helpers ──────────────────────────────────────────────────────────────

def strip_html(html: str) -> str:
    text = re.sub(r'<[^>]+>', ' ', html)
    text = (text.replace('&gt;', '>').replace('&lt;', '<').replace('&amp;', '&')
                .replace('&nbsp;', ' ').replace('&raquo;', '>>').replace('&#8805;', '>='))
    return re.sub(r'\s+', ' ', text).strip()


def read_zip_member(zip_path: Path, pattern: str) -> Optional[str]:
    rx = re.compile(pattern, re.IGNORECASE)
    with zipfile.ZipFile(zip_path) as zf:
        for name in zf.namelist():
            if rx.search(name):
                return zf.read(name).decode('utf-8', errors='replace')
    return None


# ── byte parsing ──────────────────────────────────────────────────────────────

def parse_bytes_mag(val: str, unit: str) -> int:
    v = float(val.replace(',', ''))
    u = unit.strip().upper()
    if u == 'B':  return int(v)
    if u == 'KB': return int(v * 1024)
    if u == 'MB': return int(v * 1024 ** 2)
    if u == 'GB': return int(v * 1024 ** 3)
    return int(v)


def parse_bytes_human(s: str) -> int:
    """'30.4 MB', '1,509 B', '2,765,608' (raw bytes)."""
    s = s.strip().replace(',', '')
    m = re.match(r'([\d.]+)\s*(GB|MB|KB|B)?$', s, re.IGNORECASE)
    if not m:
        return 0
    return parse_bytes_mag(m.group(1), m.group(2) or 'B')


# ── data model ────────────────────────────────────────────────────────────────

@dataclass
class MatSummary:
    heap_bytes: int = 0
    objects: int = 0
    classes: int = 0
    gc_roots: int = 0


@dataclass
class ClassRow:
    name: str
    instances: int
    shallow: int
    retained_min: int   # MAT reports ">=" so this is a lower bound


@dataclass
class Suspect:
    retained: int
    pct: float


@dataclass
class TopObject:
    cls: str
    shallow: int
    retained: int


@dataclass
class DomClass:
    """Biggest Top-Level Dominator Classes row (MAT Top Consumers report)."""
    cls: str
    retained: int


@dataclass
class MatReport:
    dump: str
    summary: MatSummary = field(default_factory=MatSummary)
    histogram: list[ClassRow] = field(default_factory=list)
    suspects: list[Suspect] = field(default_factory=list)
    top_objects: list[TopObject] = field(default_factory=list)
    dom_classes: list[DomClass] = field(default_factory=list)


# ── JSON cache helpers ────────────────────────────────────────────────────────

def _mat_report_to_dict(r: MatReport) -> dict:
    return {
        'dump': r.dump,
        'summary': asdict(r.summary),
        'histogram': [asdict(x) for x in r.histogram],
        'suspects':  [asdict(x) for x in r.suspects],
        'top_objects': [asdict(x) for x in r.top_objects],
        'dom_classes': [asdict(x) for x in r.dom_classes],
    }


def _mat_report_from_dict(d: dict) -> MatReport:
    r = MatReport(dump=d['dump'])
    s = d['summary']
    r.summary = MatSummary(**s)
    r.histogram   = [ClassRow(**x) for x in d['histogram']]
    r.suspects    = [Suspect(**x) for x in d['suspects']]
    r.top_objects = [TopObject(**x) for x in d['top_objects']]
    r.dom_classes = [DomClass(cls=x.get('cls', x.get('name', '')), retained=x.get('retained', 0))
                     for x in d.get('dom_classes', [])]
    return r


def load_mat_report_cached(stem: str, dumps_dir: Path) -> Optional[MatReport]:
    """Load MAT report from JSON cache if fresh, else parse zips and save cache."""
    ov_zip = dumps_dir / f"{stem}_System_Overview.zip"
    ls_zip = dumps_dir / f"{stem}_Leak_Suspects.zip"
    cache  = dumps_dir / f"{stem}_mat_cache.json"

    if not ov_zip.exists():
        return None

    # Use cache if it's newer than both zips AND has dom_classes (v2 format)
    if cache.exists():
        cache_mtime = cache.stat().st_mtime
        zips_mtime  = max(ov_zip.stat().st_mtime,
                          ls_zip.stat().st_mtime if ls_zip.exists() else 0)
        if cache_mtime >= zips_mtime:
            cached = json.loads(cache.read_text())
            if 'dom_classes' in cached:  # v2 format — safe to use
                return _mat_report_from_dict(cached)

    r = _parse_mat_overview_zip(ov_zip)
    r.dump = stem
    if ls_zip.exists():
        r.suspects = _parse_mat_suspects(ls_zip)

    cache.write_text(json.dumps(_mat_report_to_dict(r), indent=2))
    return r


def load_mat_reports(dumps_dir: Path) -> dict[str, MatReport]:
    reports: dict[str, MatReport] = {}
    for hprof in sorted(dumps_dir.glob("*.hprof")):
        stem = hprof.stem
        r = load_mat_report_cached(stem, dumps_dir)
        if r:
            reports[stem] = r
    return reports


# ── MAT zip parsers ───────────────────────────────────────────────────────────

def _parse_mat_overview_zip(zip_path: Path) -> MatReport:
    r = MatReport(dump='')

    html = read_zip_member(zip_path, r'^index\.html$')
    if html:
        text = strip_html(html)
        if m := re.search(r'Used heap dump\s*([\d.,]+)\s*(MB|KB|GB|B)', text):
            r.summary.heap_bytes = parse_bytes_mag(m.group(1), m.group(2))
        if m := re.search(r'Number of objects\s*([\d,]+)', text):
            r.summary.objects = int(m.group(1).replace(',', ''))
        if m := re.search(r'Number of classes\s*([\d,]+)', text):
            r.summary.classes = int(m.group(1).replace(',', ''))
        if m := re.search(r'Number of GC roots\s*([\d,]+)', text):
            r.summary.gc_roots = int(m.group(1).replace(',', ''))

    html = read_zip_member(zip_path, r'Class_Histogram')
    if html:
        text = strip_html(html)
        for m in re.finditer(
            r'([\w.$\[\]]+)\s+All objects\s+([\d,]+)\s+([\d,]+)\s*>=\s*([\d,]+)',
            text
        ):
            r.histogram.append(ClassRow(
                name=m.group(1),
                instances=int(m.group(2).replace(',', '')),
                shallow=int(m.group(3).replace(',', '')),
                retained_min=int(m.group(4).replace(',', '')),
            ))

    html = read_zip_member(zip_path, r'Top_Consumers')
    if html:
        # Find the Biggest Objects table (before Biggest Top-Level Dominator Classes)
        obj_end_idx = html.find('Biggest Top-Level Dominator Classes')
        obj_html = html[:obj_end_idx] if obj_end_idx > 0 else html

        # Parse table rows: each row has <td> with the anchor, <td>shallow</td>, <td>retained</td>
        # The anchor text is like:
        #   "java.util.concurrent.ForkJoinWorkerThread @ 0xc0303be0  ForkJoinPool.commonPool-worker-16"
        #   "class scala.runtime.LazyVals$ @ 0xce724538"
        for row_m in re.finditer(r'<tr>(.*?)</tr>', obj_html, re.DOTALL):
            row = row_m.group(1)
            # Extract the href anchor text (object description)
            href_m = re.search(r'<a href="mat://object/0x[0-9a-f]+">(.*?)</a>', row, re.DOTALL)
            if not href_m:
                continue
            anchor = strip_html(href_m.group(1)).strip()
            # anchor: "ClassName @ 0xHEX  optional-name" or "class ClassName @ 0xHEX"
            # Strip "class " prefix for class objects
            if anchor.startswith('class '):
                anchor = anchor[6:]
            cls_m = re.match(r'([\w.$\[\]]+)\s+@\s+0x', anchor)
            if not cls_m:
                continue
            cls = cls_m.group(1)
            # Extract the two numeric <td align="right"> cells (shallow, retained)
            nums = re.findall(r'<td align="right">([\d,]*)</td>', row)
            if len(nums) < 2:
                continue
            try:
                shallow  = int(nums[0].replace(',', '')) if nums[0] else 0
                retained = int(nums[1].replace(',', '')) if nums[1] else 0
            except ValueError:
                continue
            r.top_objects.append(TopObject(cls=cls, shallow=shallow, retained=retained))

        # Parse Biggest Top-Level Dominator Classes table.
        # Format per row: "ClassName [Only object | First N of M objects] count shallow retained pct%"
        if obj_end_idx > 0:
            dom_section = html[obj_end_idx:]
            for tbl_m in re.finditer(r'<table[^>]*>(.*?)</table>', dom_section, re.DOTALL):
                tbl = tbl_m.group(1)
                rows = re.findall(r'<tr>(.*?)</tr>', tbl, re.DOTALL)
                if len(rows) <= 1:
                    continue
                for row in rows[1:]:
                    text = re.sub(r'<[^>]+>', ' ', row)
                    text = re.sub(r'\s+', ' ', text).strip()
                    if not text or 'Label' in text:
                        continue
                    # Strip "class " prefix that sometimes appears
                    if text.startswith('class '):
                        text = text[6:]
                    m = re.match(
                        r'([\w.$\[\]]+)\s+(?:Only object|First \d+ of \d+ objects)\s+'
                        r'(\d[\d,]*)\s+(\d[\d,]*)\s+(\d[\d,]*)',
                        text
                    )
                    if m:
                        r.dom_classes.append(DomClass(
                            cls=m.group(1),
                            retained=int(m.group(4).replace(',', ''))
                        ))

    return r


def _parse_mat_suspects(zip_path: Path) -> list[Suspect]:
    suspects: list[Suspect] = []
    html = read_zip_member(zip_path, r'^index\.html$')
    if not html:
        return suspects
    text = strip_html(html)
    for m in re.finditer(r'Problem Suspect \d+.*?([\d,]+) \(([\d.]+)%\) bytes', text):
        suspects.append(Suspect(
            retained=int(m.group(1).replace(',', '')),
            pct=float(m.group(2)),
        ))
    return suspects


# ── our Markdown report parser ────────────────────────────────────────────────

@dataclass
class OurReport:
    dump: str
    heap_bytes: int = 0
    objects: int = 0
    classes: int = 0
    gc_roots: int = 0
    histogram: list[ClassRow] = field(default_factory=list)
    suspects: list[Suspect] = field(default_factory=list)
    top_objects: list[TopObject] = field(default_factory=list)
    dom_classes: list[DomClass] = field(default_factory=list)


def parse_our_report(md: str, stem: str) -> OurReport:
    r = OurReport(dump=stem)
    lines = md.splitlines()

    for line in lines:
        if m := re.search(r'\|\s*Total shallow heap\s*\|\s*(.+?)\s*\|', line):
            r.heap_bytes = parse_bytes_human(m.group(1))
        if m := re.search(r'\|\s*Total objects\s*\|\s*([\d,]+)', line):
            r.objects = int(m.group(1).replace(',', ''))
        if m := re.search(r'\|\s*Classes loaded\s*\|\s*([\d,]+)', line):
            r.classes = int(m.group(1).replace(',', ''))
        if m := re.search(r'\|\s*GC roots\s*\|\s*([\d,]+)', line):
            r.gc_roots = int(m.group(1).replace(',', ''))

    in_histo = False
    for line in lines:
        if '### Class Histogram' in line:
            in_histo = True; continue
        if in_histo and line.startswith('##'):
            break
        if in_histo:
            if m := re.match(
                r'\|\s*\d+\s*\|\s*`([^`]+)`\s*\|\s*([\d,]+)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|',
                line
            ):
                r.histogram.append(ClassRow(
                    name=m.group(1),
                    instances=int(m.group(2).replace(',', '')),
                    shallow=parse_bytes_human(m.group(3)),
                    retained_min=parse_bytes_human(m.group(4)),
                ))

    # "- **Retained heap**: 22.8 MB (75.1% of total)"
    for m in re.finditer(r'\*\*Retained heap\*\*:\s*([\d.,]+ (?:GB|MB|KB|B))\s*\(([0-9.]+)%', md):
        r.suspects.append(Suspect(
            retained=parse_bytes_human(m.group(1).strip()),
            pct=float(m.group(2)),
        ))

    # "| N | <idx> | `ClassName` | shallow | retained (pct%) |"
    in_top = False
    for line in lines:
        if '### Biggest Objects' in line:
            in_top = True; continue
        if in_top and line.startswith('##'):
            break
        if in_top:
            if m := re.match(
                r'\|\s*\d+\s*\|\s*\d+\s*\|\s*`([^`]+)`\s*\|\s*([^|]+?)\s*\|\s*([^|(]+?)\s*\(',
                line
            ):
                r.top_objects.append(TopObject(
                    cls=m.group(1),
                    shallow=parse_bytes_human(m.group(2)),
                    retained=parse_bytes_human(m.group(3)),
                ))

    # "| N | `ClassName` | instances | retained |"  (Biggest Classes by Retained Heap)
    in_dom = False
    for line in lines:
        if '### Biggest Classes by Retained Heap' in line:
            in_dom = True; continue
        if in_dom and line.startswith('##'):
            break
        if in_dom:
            if m := re.match(
                r'\|\s*\d+\s*\|\s*`([^`]+)`\s*\|\s*([\d,]+)\s*\|\s*([^|]+?)\s*\|',
                line
            ):
                r.dom_classes.append(DomClass(
                    cls=m.group(1),
                    retained=parse_bytes_human(m.group(3)),
                ))

    return r


# ── run our tool ──────────────────────────────────────────────────────────────

def _timeout_for(hprof: Path) -> int:
    """Scale timeout by file size: 1200s for >2 GB, 600s otherwise."""
    try:
        return 1200 if os.path.getsize(hprof) > 2 * 1024 ** 3 else 600
    except OSError:
        return 600


def run_our_tool(jar: Path, hprof: Path, out_md: Path) -> bool:
    result = subprocess.run(
        ['java', '-Xss4m', '-jar', str(jar), 'views', str(hprof), str(out_md)],
        capture_output=True, text=True, timeout=_timeout_for(hprof)
    )
    return result.returncode == 0


# ── benchmark ─────────────────────────────────────────────────────────────────

def measure_run(cmd: list, timeout: int = 600) -> 'tuple[float, int] | None':
    """Wrap cmd with /usr/bin/time -v and parse wall time + peak RSS.

    Returns (wall_seconds, rss_bytes) or None on failure.
    """
    wrapped = ['/usr/bin/time', '-v'] + cmd
    try:
        result = subprocess.run(
            wrapped, capture_output=True, text=True, timeout=timeout
        )
    except subprocess.TimeoutExpired:
        return None
    except Exception:
        return None

    # /usr/bin/time -v writes its report to stderr
    stderr = result.stderr

    # Parse elapsed wall-clock time — two formats:
    #   "Elapsed (wall clock) time (h:mm:ss or m:ss): 0:14.12"
    #   "Elapsed (wall clock) time (h:mm:ss or m:ss): 0:01:04.53"
    # Use a greedy match past the closing ')' to find the last ':' before the value.
    wall_seconds: Optional[float] = None
    m = re.search(r'Elapsed \(wall clock\) time \([^)]+\):\s*(\S+)', stderr)
    if m:
        raw = m.group(1)
        parts = raw.split(':')
        if len(parts) == 2:
            # M:SS.cs
            wall_seconds = int(parts[0]) * 60 + float(parts[1])
        elif len(parts) == 3:
            # H:MM:SS.cs
            wall_seconds = int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])

    # Parse maximum resident set size (in KB on Linux)
    rss_kb: Optional[int] = None
    m2 = re.search(r'Maximum resident set size \(kbytes\):\s*(\d+)', stderr)
    if m2:
        rss_kb = int(m2.group(1))

    if wall_seconds is None or rss_kb is None:
        return None

    return (wall_seconds, rss_kb * 1024)


def _fmt_bytes_human(n: int) -> str:
    """Human-readable bytes: '62.1 MB', '310.4 MB', etc."""
    if n >= 1024 ** 3:
        return f"{n / 1024 ** 3:.1f} GB"
    if n >= 1024 ** 2:
        return f"{n / 1024 ** 2:.1f} MB"
    if n >= 1024:
        return f"{n / 1024:.1f} KB"
    return f"{n} B"


def run_benchmark(args) -> None:
    """Measure wall time and peak RSS for our tool and MAT on every .hprof in dumps_dir."""
    gnu_time = '/usr/bin/time'
    # Verify /usr/bin/time exists and supports -v
    if not Path(gnu_time).exists():
        print(f"ERROR: {gnu_time} not found — benchmark requires GNU time (Linux only)",
              file=sys.stderr)
        sys.exit(2)
    probe = subprocess.run(
        [gnu_time, '-v', 'true'], capture_output=True, text=True
    )
    if 'Maximum resident set size' not in probe.stderr:
        print(f"ERROR: {gnu_time} does not support -v (need GNU time, not BSD time)",
              file=sys.stderr)
        sys.exit(2)

    jar = args.jar
    mat_sh = args.mat_sh
    dumps_dir: Path = args.dumps_dir

    # Collect hprof files (filtered by --dump / --skip / --max-size)
    max_bytes = args.max_size * 1024 * 1024
    hprofs: list[Path] = sorted(dumps_dir.glob('*.hprof'))
    if args.dump:
        hprofs = [h for h in hprofs if h.stem == args.dump]
    if args.skip:
        hprofs = [h for h in hprofs if h.stem not in args.skip]
    hprofs = [h for h in hprofs if os.path.getsize(h) <= max_bytes]

    if not hprofs:
        print("Benchmark: no .hprof files found — nothing to measure.")
        return

    @dataclass
    class BenchRow:
        name: str        # display name (stem, no timestamp)
        file_size: int   # bytes
        objects: Optional[int]
        ours_rss: Optional[int]
        mat_rss: Optional[int]
        ours_t: Optional[float]
        mat_t: Optional[float]

    import re as _re

    def _read_object_count(md_path: Path) -> Optional[int]:
        """Parse '| Total objects | 952,660 |' from ours.md."""
        try:
            for line in md_path.read_text().splitlines():
                m = _re.search(r'Total objects\s*\|\s*([\d,]+)', line)
                if m:
                    return int(m.group(1).replace(',', ''))
        except Exception:
            pass
        return None

    def _fmt_objects(n: Optional[int]) -> str:
        if n is None:
            return '?'
        if n >= 1_000_000:
            return f"{n / 1_000_000:.1f}M"
        if n >= 1_000:
            return f"{n // 1000}K"
        return str(n)

    def _load_mat_bench_cache(hprof: Path, dumps_dir: Path) -> Optional[tuple[float, int]]:
        cache_file = dumps_dir / f"{hprof.stem}_mat_bench_cache.json"
        if not cache_file.exists():
            return None
        try:
            stat = hprof.stat()
            cached = json.loads(cache_file.read_text())
            if (cached.get('hprof_mtime') == stat.st_mtime
                    and cached.get('hprof_size') == stat.st_size):
                return (cached['wall_seconds'], cached['rss_bytes'])
        except Exception:
            pass
        return None

    def _save_mat_bench_cache(hprof: Path, dumps_dir: Path, result: tuple[float, int]) -> None:
        stat = hprof.stat()
        cache_file = dumps_dir / f"{hprof.stem}_mat_bench_cache.json"
        cache_file.write_text(json.dumps({
            'wall_seconds': result[0],
            'rss_bytes': result[1],
            'hprof_mtime': stat.st_mtime,
            'hprof_size': stat.st_size,
        }))

    def _display_name(hprof: Path) -> str:
        """Strip trailing _PID_TIMESTAMP suffix if present."""
        stem = hprof.stem
        m = _re.match(r'^(.+?)_\d+_\d{8}_\d{6}$', stem)
        return m.group(1) if m else stem

    def _bench_one(hprof: Path) -> BenchRow:
        file_size = os.path.getsize(hprof)
        name = _display_name(hprof)

        # Our tool
        ours = measure_run(
            ['java', '-Xss4m', '-jar', str(jar), 'views', str(hprof), '/dev/null'],
            timeout=_timeout_for(hprof),
        )

        # Object count from cached ours.md (generated during parity check)
        md_path = dumps_dir / f"{hprof.stem}_ours.md"
        objects = _read_object_count(md_path)

        # MAT: cache-first
        mat = _load_mat_bench_cache(hprof, dumps_dir)
        if mat is None:
            mat = measure_run([str(mat_sh), str(hprof), 'org.eclipse.mat.api:overview'])
            if mat:
                _save_mat_bench_cache(hprof, dumps_dir, mat)

        return BenchRow(
            name=name,
            file_size=file_size,
            objects=objects,
            ours_rss=ours[1] if ours else None,
            mat_rss=mat[1] if mat else None,
            ours_t=ours[0] if ours else None,
            mat_t=mat[0] if mat else None,
        )

    # Count cached vs uncached MAT runs for progress message
    cached_count = sum(1 for h in hprofs if _load_mat_bench_cache(h, dumps_dir) is not None)
    uncached_count = len(hprofs) - cached_count
    print(f"\nRunning benchmarks on {len(hprofs)} dump(s) in parallel "
          f"({cached_count} MAT cached, {uncached_count} MAT fresh)…")

    rows: list[BenchRow] = []
    with ThreadPoolExecutor(max_workers=min(len(hprofs), 8)) as pool:
        futures = {pool.submit(_bench_one, h): h for h in hprofs}
        for fut in as_completed(futures):
            hprof = futures[fut]
            try:
                row = fut.result()
            except Exception as e:
                row = BenchRow(
                    name=_display_name(hprof),
                    file_size=os.path.getsize(hprof),
                    objects=None, ours_rss=None, mat_rss=None, ours_t=None, mat_t=None,
                )
                print(f"  ERROR {hprof.name}: {e}", file=sys.stderr)
            rows.append(row)
            status = (f"  ✓ {row.name:<30} "
                      f"ours={row.ours_t:.1f}s/{row.ours_rss//1024//1024}MB  "
                      f"MAT={row.mat_t:.1f}s/{row.mat_rss//1024//1024}MB"
                      if row.ours_t and row.mat_t else f"  ✗ {row.name}")
            print(status)

    # Sort by file size; only show dumps ≥ 50 MB in the table
    rows.sort(key=lambda r: r.file_size)
    rows = [r for r in rows if r.file_size >= 50 * 1024 * 1024]
    if not rows:
        print("No dumps ≥ 50 MB — benchmark table omitted.")
        return

    # ── boxed table ───────────────────────────────────────────────────────────
    col_w = max(len(r.name) for r in rows)

    def _mb(n: Optional[int]) -> str:
        return f"{round(n / 1024 / 1024)} MB" if n is not None else '?'

    def _ts(t: Optional[float]) -> str:
        return f"{t:.1f}s" if t is not None else '?'

    H = ['Dump', 'MB', 'Objects', 'ours time', 'ours RSS', 'MAT time', 'MAT RSS']
    widths = [
        max(col_w, len(H[0])),
        max(4, len(H[1])),
        max(7, len(H[2])),
        max(9, len(H[3])),
        max(8, len(H[4])),
        max(8, len(H[5])),
        max(7, len(H[6])),
    ]

    def _row_line(cols: list[str]) -> str:
        return ('│ ' + ' │ '.join(c.rjust(w) if i > 0 else c.ljust(w)
                                  for i, (c, w) in enumerate(zip(cols, widths))) + ' │')

    def _sep_line(left: str, mid: str, right: str) -> str:
        return left + mid.join('─' * (w + 2) for w in widths) + right

    print()
    print(_sep_line('┌', '┬', '┐'))
    print(_row_line(H))
    print(_sep_line('├', '┼', '┤'))

    for i, r in enumerate(rows):
        file_mb = str(round(r.file_size / 1024 / 1024))
        cols = [
            r.name,
            file_mb,
            _fmt_objects(r.objects),
            _ts(r.ours_t),
            _mb(r.ours_rss),
            _ts(r.mat_t),
            _mb(r.mat_rss),
        ]
        print(_row_line(cols))
        if i < len(rows) - 1:
            print(_sep_line('├', '┼', '┤'))

    print(_sep_line('└', '┴', '┘'))

    print()
    # Goals summary
    rss_pass = sum(1 for r in rows if r.ours_rss and r.file_size and r.ours_rss <= r.file_size / 2)
    speed_pass = sum(1 for r in rows if r.ours_t and r.mat_t and r.mat_t / r.ours_t >= 3.0)
    print(f" RSS goal  (ours ≤ filesize/2): {rss_pass}/{len(rows)}")
    print(f" Speed goal (speedup ≥ 3×):     {speed_pass}/{len(rows)}")
    print()


def _run_one(args_tuple) -> tuple[str, bool]:
    """Worker for parallel generation. Returns (stem, success)."""
    stem, jar, hprof, md_out = args_tuple
    ok = run_our_tool(jar, hprof, md_out)
    return stem, ok


def generate_reports_parallel(
    stems: list[str], jar: Path, dumps_dir: Path, max_workers: int = 10
) -> dict[str, bool]:
    """Run hprof-redact on all stems in parallel. Returns {stem: success}."""
    tasks = [
        (stem, jar, dumps_dir / f"{stem}.hprof", dumps_dir / f"{stem}_ours.md")
        for stem in stems
    ]
    results: dict[str, bool] = {}
    print(f"\nGenerating {len(tasks)} report(s) in parallel (workers={max_workers})…")
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = {pool.submit(_run_one, t): t[0] for t in tasks}
        for fut in as_completed(futures):
            stem, ok = fut.result()
            status = "done" if ok else "FAILED"
            print(f"  {stem}: {status}")
            results[stem] = ok
    return results


# ── comparison engine ─────────────────────────────────────────────────────────

@dataclass
class Check:
    label: str
    mat_val: float
    our_val: float
    unit: str = ''
    is_ge: bool = False   # MAT retained is a lower bound; ours just needs to be >= mat*(1-tol)

    @property
    def diff_pct(self) -> float:
        if self.mat_val == 0:
            return 0.0 if self.our_val == 0 else 100.0
        return (self.our_val - self.mat_val) / self.mat_val * 100.0

    @property
    def ok(self) -> bool:
        if self.mat_val == 0 and self.our_val == 0:
            return True
        abs_diff = abs(self.our_val - self.mat_val)
        floor = ABS_FLOOR_BYTES if self.unit == 'bytes' else 0
        if self.is_ge:
            return self.our_val >= self.mat_val * (1.0 - REL_TOL)
        return abs_diff <= max(abs(self.mat_val) * REL_TOL, floor)


def _c(label, mat, our, unit='', is_ge=False) -> Check:
    return Check(label, float(mat), float(our), unit, is_ge)


def compare(mat: MatReport, our: OurReport) -> list[Check]:
    checks: list[Check] = []

    def norm_cls(name: str) -> str:
        """Normalize array class names: strip trailing [N] (MAT) → [] (ours)."""
        return re.sub(r'\[\d+\]', '[]', name)

    checks += [
        _c('overview/heap_bytes', mat.summary.heap_bytes, our.heap_bytes,  'bytes'),
        _c('overview/objects',    mat.summary.objects,    our.objects),
        _c('overview/classes',    mat.summary.classes,    our.classes),
        _c('overview/gc_roots',   mat.summary.gc_roots,   our.gc_roots),
    ]

    # Aggregate histogram rows by name (handles duplicate class names from multiple classloaders).
    our_histo: dict[str, ClassRow] = {}
    for row in our.histogram:
        if row.name in our_histo:
            existing = our_histo[row.name]
            our_histo[row.name] = ClassRow(
                name=row.name,
                instances=existing.instances + row.instances,
                shallow=existing.shallow + row.shallow,
                retained_min=existing.retained_min + row.retained_min,
            )
        else:
            our_histo[row.name] = row
    for mat_row in mat.histogram:
        if re.fullmatch(r'0x[0-9a-fA-F]+', mat_row.name):
            continue  # skip rows MAT couldn't resolve to a class name
        our_row = our_histo.get(mat_row.name)
        p = f'hist/{mat_row.name}'
        if our_row is None:
            checks += [
                _c(f'{p}/instances', mat_row.instances,    0),
                _c(f'{p}/shallow',   mat_row.shallow,      0, 'bytes'),
                _c(f'{p}/retained',  mat_row.retained_min, 0, 'bytes', True),
            ]
        else:
            checks += [
                _c(f'{p}/instances', mat_row.instances,    our_row.instances),
                _c(f'{p}/shallow',   mat_row.shallow,      our_row.shallow,      'bytes'),
                _c(f'{p}/retained',  mat_row.retained_min, our_row.retained_min, 'bytes', True),
            ]

    checks.append(_c('suspects/count', len(mat.suspects), len(our.suspects)))
    if mat.suspects and our.suspects:
        checks += [
            _c('suspects/top1_retained', mat.suspects[0].retained, our.suspects[0].retained, 'bytes'),
            _c('suspects/top1_pct',      mat.suspects[0].pct,      our.suspects[0].pct),
        ]

    mat_n = min(20, len(mat.top_objects))
    if mat_n > 0:
        mat_cls = Counter(norm_cls(o.cls) for o in mat.top_objects[:mat_n])
        our_cls = Counter(norm_cls(o.cls) for o in our.top_objects[:mat_n])
        overlap = sum((mat_cls & our_cls).values())
        checks.append(_c(f'top_objects/top{mat_n}_class_overlap', mat_n, overlap))
    if mat.top_objects and our.top_objects:
        checks.append(_c('top_objects/top1_retained',
                         mat.top_objects[0].retained, our.top_objects[0].retained, 'bytes'))

    # Biggest Top-Level Dominator Classes: compare class-name overlap and top-3 retained values
    dom_n = min(10, len(mat.dom_classes))
    if dom_n > 0 and our.dom_classes:
        mat_dom_cls = Counter(norm_cls(d.cls) for d in mat.dom_classes[:dom_n])
        our_dom_cls = Counter(norm_cls(d.cls) for d in our.dom_classes[:dom_n])
        dom_overlap = sum((mat_dom_cls & our_dom_cls).values())
        checks.append(_c(f'dom_classes/top{dom_n}_overlap', dom_n, dom_overlap))
    # Per-class retained for top-3 dominator classes (by MAT order)
    our_dom_by_cls = {norm_cls(d.cls): d.retained for d in our.dom_classes}
    for rank, dc in enumerate(mat.dom_classes[:3], 1):
        nc = norm_cls(dc.cls)
        our_ret = our_dom_by_cls.get(nc, 0)
        checks.append(_c(f'dom_classes/rank{rank}_{nc}/retained', dc.retained, our_ret, 'bytes', True))

    return checks


# ── reporting ─────────────────────────────────────────────────────────────────

def fmt_val(v: float, unit: str, is_ge: bool = False) -> str:
    if unit == 'bytes':
        if v >= 1024 ** 3: s = f"{v / 1024 ** 3:.2f} GB"
        elif v >= 1024 ** 2: s = f"{v / 1024 ** 2:.1f} MB"
        elif v >= 1024:      s = f"{v / 1024:.1f} KB"
        else:                s = f"{int(v)} B"
    else:
        s = f"{int(v):,}"
    return (">=" + s) if is_ge else s


def print_dump_report(dump: str, checks: list[Check],
                      use_color: bool, failures_only: bool) -> int:
    failures = [c for c in checks if not c.ok]
    w = 58
    if failures_only and not failures:
        print(f"  {'OK':<6} {dump}")
        return 0

    print(f"\n{'='*90}")
    b, reset = (ANSI_BOLD, ANSI_RESET) if use_color else ('', '')
    print(f"  {b}{dump}{reset}")
    print(f"{'='*90}")
    print(f"  {'Check':<{w}}  {'MAT':>16}  {'Ours':>14}  {'Diff':>8}")
    print(f"  {'-'*w}  {'-'*16}  {'-'*14}  {'-'*8}")
    for c in checks:
        if failures_only and c.ok:
            continue
        diff = f"{c.diff_pct:+.1f}%"
        mat_s = fmt_val(c.mat_val, c.unit, c.is_ge)
        our_s = fmt_val(c.our_val, c.unit)
        if use_color:
            col  = ANSI_GREEN if c.ok else ANSI_RED
            mark = "✓" if c.ok else "✗"
            print(f"  {col}{mark}{reset} {c.label:<{w-2}}  {mat_s:>16}  {our_s:>14}  {diff:>8}")
        else:
            mark = "OK  " if c.ok else "FAIL"
            print(f"  [{mark}] {c.label:<{w-6}}  {mat_s:>16}  {our_s:>14}  {diff:>8}")
    return len(failures)


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument('--dumps-dir',     required=True, type=Path,
                    help='Directory with *.hprof + MAT zip reports')
    ap.add_argument('--jar',           required=True, type=Path,
                    help='hprof-redact fat jar')
    ap.add_argument('--dump',          help='Limit to one dump stem, e.g. dump_2_scala-doku')
    ap.add_argument('--skip',          action='append', default=[],
                    metavar='STEM',    help='Skip this dump stem (repeatable)')
    ap.add_argument('--rerun',         action='store_true',
                    help='Re-run hprof-redact even if _ours.md already exists')
    ap.add_argument('--failures-only', action='store_true',
                    help='Print only FAIL lines; passing dumps show one summary line')
    ap.add_argument('--no-color',      action='store_true')
    ap.add_argument('--workers',       type=int, default=10,
                    help='Parallel workers for report generation (default: 10)')
    ap.add_argument('--benchmark',     action='store_true',
                    help='Measure wall time and peak RSS for our tool and MAT')
    ap.add_argument('--mat-sh',        type=Path,
                    help='Path to MAT ParseHeapDump.sh (required with --benchmark)')
    ap.add_argument('--max-size',      type=int, default=2000,
                    metavar='MB',      help='Skip dumps larger than this many MB (default: 2000)')
    args = ap.parse_args()

    if args.benchmark:
        if not args.mat_sh:
            ap.error('--mat-sh is required when --benchmark is set')
        run_benchmark(args)

    use_color = not args.no_color and sys.stdout.isatty()

    print("Loading MAT reports (cached)…")
    mat_reports = load_mat_reports(args.dumps_dir)
    if not mat_reports:
        print("ERROR: no MAT reports found in", args.dumps_dir, file=sys.stderr)
        sys.exit(1)
    print(f"Found {len(mat_reports)} MAT report(s): {', '.join(mat_reports)}")

    # Filter to requested dump if --dump given; exclude --skip dumps; apply --max-size
    max_bytes = args.max_size * 1024 * 1024
    def _hprof_size(stem: str) -> int:
        p = args.dumps_dir / f"{stem}.hprof"
        return os.path.getsize(p) if p.exists() else 0
    stems_to_run = sorted(
        s for s in mat_reports
        if (not args.dump or s == args.dump)
        and s not in args.skip
        and _hprof_size(s) <= max_bytes
    )

    # Collect stems that need (re)generation
    to_generate = [
        s for s in stems_to_run
        if args.rerun or not (args.dumps_dir / f"{s}_ours.md").exists()
    ]
    skipped: set[str] = set()

    if to_generate:
        gen_results = generate_reports_parallel(
            to_generate, args.jar, args.dumps_dir, max_workers=args.workers
        )
        skipped = {s for s, ok in gen_results.items() if not ok}
    else:
        if not args.failures_only:
            print("\nAll reports already cached (use --rerun to regenerate).")

    def _compare_one(stem: str):
        md_out = args.dumps_dir / f"{stem}_ours.md"
        if not md_out.exists():
            return stem, None
        our_r  = parse_our_report(md_out.read_text(), stem)
        checks = compare(mat_reports[stem], our_r)
        return stem, checks

    valid_stems = [s for s in stems_to_run if s not in skipped]
    stem_checks: dict[str, list[Check]] = {}
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for stem, checks in pool.map(_compare_one, valid_stems):
            if checks is None:
                print(f"  Skipping {stem} (no output file)", file=sys.stderr)
            else:
                stem_checks[stem] = checks

    total_fail = 0
    for stem in stems_to_run:
        if stem in skipped:
            print(f"  Skipping {stem} (generation failed)", file=sys.stderr)
            continue
        if stem not in stem_checks:
            continue
        fails = print_dump_report(stem, stem_checks[stem], use_color, args.failures_only)
        total_fail += fails

    print(f"\n{'='*90}")
    if total_fail == 0:
        msg = "All checks passed."
        print(f"  {ANSI_GREEN if use_color else ''}✓ {msg}{ANSI_RESET if use_color else ''}")
    else:
        msg = f"{total_fail} check(s) failed."
        print(f"  {ANSI_RED if use_color else ''}✗ {msg}{ANSI_RESET if use_color else ''}")
    print()
    sys.exit(0 if total_fail == 0 else 1)


if __name__ == '__main__':
    main()
