#!/usr/bin/env bash
# Capture a large hprof dump from one of the calibration workloads.
#
# Modes:
#   petclinic   start Spring Petclinic with EnterpriseMemoryProfile and dump once ready
#   hashmap     run HashMapHeavyDump with the configured profile and dump in-process
#
# Common env:
#   OUT          output hprof path                (default: /tmp/mat-calibration/<mode>-<size>.hprof)
#   XMX          JVM max heap                     (default: 3g)
#   PROFILE      named size: small|medium|large   (default: large)
#                  small  ≈ 300 MB,  medium ≈ 1 GB,  large ≈ 2 GB
#
# Petclinic-specific:
#   JAR          built jar path                   (default: /tmp/mat-calibration/spring-petclinic/target/<latest>.jar)
#   READY_FILE   marker file path                 (default: /tmp/petclinic-memory-profile-ready)
#
# HashMap-specific:
#   PROGRAM_DIR  dir containing HashMapHeavyDump.class
#                (default: $(dirname $0)/test_programs, compiled on demand)
set -euo pipefail

MODE="${1:-petclinic}"
PROFILE="${PROFILE:-large}"
XMX="${XMX:-3g}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

case "$PROFILE" in
    small)  PC_OWNERS=5000;  PC_HISTORY=20000;  PC_BLOBS=1000;  HM_MAPS=50;  HM_ENTRIES=10000 ;;
    medium) PC_OWNERS=10000; PC_HISTORY=50000;  PC_BLOBS=2500;  HM_MAPS=100; HM_ENTRIES=25000 ;;
    large)  PC_OWNERS=20000; PC_HISTORY=100000; PC_BLOBS=5000;  HM_MAPS=200; HM_ENTRIES=50000 ;;
    *) echo "unknown PROFILE=$PROFILE (use small|medium|large)" >&2; exit 1 ;;
esac

OUT="${OUT:-/tmp/mat-calibration/${MODE}-${PROFILE}.hprof}"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"

case "$MODE" in
    petclinic)
        JAR="${JAR:-$(ls /tmp/mat-calibration/spring-petclinic/target/spring-petclinic-*.jar 2>/dev/null | grep -v sources | head -n1 || true)}"
        if [[ -z "$JAR" || ! -f "$JAR" ]]; then
            echo "no Petclinic jar at /tmp/mat-calibration/spring-petclinic/target — run spring-petclinic/setup.sh first" >&2
            exit 1
        fi
        READY_FILE="${READY_FILE:-/tmp/petclinic-memory-profile-ready}"
        rm -f "$READY_FILE"

        echo "[capture] starting Petclinic, jar=$JAR Xmx=$XMX owners=$PC_OWNERS history=$PC_HISTORY blobs=$PC_BLOBS"
        java -Xmx"$XMX" \
             -Dprofile.ownerCount="$PC_OWNERS" \
             -Dprofile.requestHistory="$PC_HISTORY" \
             -Dprofile.blobCount="$PC_BLOBS" \
             -Dprofile.readyFile="$READY_FILE" \
             -jar "$JAR" >/tmp/mat-calibration/petclinic.log 2>&1 &
        PID=$!
        trap 'kill -TERM '"$PID"' 2>/dev/null || true' EXIT

        echo "[capture] pid=$PID, waiting for $READY_FILE (up to 5 min)"
        for i in $(seq 1 300); do
            if [[ -f "$READY_FILE" ]]; then break; fi
            if ! kill -0 "$PID" 2>/dev/null; then
                echo "[capture] JVM died — tail of log:" >&2
                tail -n 50 /tmp/mat-calibration/petclinic.log >&2
                exit 1
            fi
            sleep 1
        done
        if [[ ! -f "$READY_FILE" ]]; then
            echo "[capture] timed out waiting for $READY_FILE" >&2
            exit 1
        fi

        echo "[capture] ready: $(cat "$READY_FILE")"
        echo "[capture] dumping to $OUT"
        jcmd "$PID" GC.heap_dump "$OUT"

        kill -TERM "$PID" 2>/dev/null || true
        wait "$PID" 2>/dev/null || true
        trap - EXIT
        ;;

    hashmap)
        PROGRAM_DIR="${PROGRAM_DIR:-${SCRIPT_DIR}/test_programs}"
        if [[ ! -f "$PROGRAM_DIR/HashMapHeavyDump.class" ]]; then
            echo "[capture] compiling HashMapHeavyDump.java"
            (cd "$PROGRAM_DIR" && javac HashMapHeavyDump.java)
        fi
        echo "[capture] running HashMapHeavyDump Xmx=$XMX maps=$HM_MAPS entries=$HM_ENTRIES"
        java -Xmx"$XMX" \
             -Dmaps="$HM_MAPS" \
             -Dentries="$HM_ENTRIES" \
             -DdumpPath="$OUT" \
             -cp "$PROGRAM_DIR" HashMapHeavyDump
        ;;

    *)
        echo "unknown mode: $MODE (use petclinic|hashmap)" >&2
        exit 1
        ;;
esac

size=$(stat -f%z "$OUT" 2>/dev/null || stat -c%s "$OUT")
size_mb=$(( size / 1024 / 1024 ))
echo "[capture] done: $OUT (${size_mb} MB)"
