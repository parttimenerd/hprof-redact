#!/bin/bash
# hprof-views — wrapper that runs hprof-tools views with optimal G1 GC flags.
# Usage: hprof-views [options] <input.hprof> [output.md]
# All arguments are passed through to the views subcommand unchanged.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${HPROF_TOOLS_JAR:-$SCRIPT_DIR/hprof-tools.jar}"

if [ ! -f "$JAR" ]; then
    echo "hprof-views: cannot find hprof-tools.jar (set HPROF_TOOLS_JAR or place it next to this script)" >&2
    exit 1
fi

exec java \
    -XX:+UseG1GC \
    -XX:G1PeriodicGCInterval=20 \
    -XX:+G1PeriodicGCInvokesConcurrent \
    -XX:MinHeapFreeRatio=2 \
    -XX:MaxHeapFreeRatio=2 \
    -XX:G1HeapRegionSize=2m \
    -XX:G1PeriodicGCSystemLoadThreshold=0.0 \
    -XX:SoftRefLRUPolicyMSPerMB=500 \
    ${JAVA_OPTS:-} \
    -jar "$JAR" views "$@"
