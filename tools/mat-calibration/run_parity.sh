#!/usr/bin/env bash
# Build, sync jar, run parity on all dumps.
#   Default: 10 standard dumps (≤2 GB) + vscode, 10 parallel workers.
#   --big:   include the 34 GB customer dump (1 worker, 1200s timeout).
set -e

BIG=0
for arg in "$@"; do
  case "$arg" in
    --big) BIG=1 ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

cd "$(git rev-parse --show-toplevel)"
echo "=== Building ==="
mvn package -q -DskipTests
scp -q target/hprof-tools.jar thinkstation:~/hprof-redact/hprof-tools.jar
echo "=== Synced jar ==="

# Also sync latest compare_parity.py
scp -q tools/mat-calibration/compare_parity.py thinkstation:~/hprof-redact/compare_parity.py

if [ "$BIG" -eq 1 ]; then
  echo "=== Running parity (all dumps incl. 34 GB, 1 worker) ==="
  ssh thinkstation "python3 ~/hprof-redact/compare_parity.py \
      --dumps-dir ~/test-heapdumps \
      --jar ~/hprof-redact/hprof-tools.jar \
      --max-size 50000 \
      --workers 1"
else
  echo "=== Running parity (10 dumps incl. vscode, 10 workers) ==="
  ssh thinkstation "python3 ~/hprof-redact/compare_parity.py \
      --dumps-dir ~/test-heapdumps \
      --jar ~/hprof-redact/hprof-tools.jar \
      --workers 10"
fi
