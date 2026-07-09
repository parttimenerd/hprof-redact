#!/usr/bin/env bash
# Clones Spring Petclinic, patches in EnterpriseMemoryProfile.java, builds the jar.
#
# Output:  ${WORK_DIR}/spring-petclinic/target/spring-petclinic-*.jar
#
# Env overrides:
#   WORK_DIR   parent dir for the clone           (default: /tmp/mat-calibration)
#   REPO_URL   Petclinic git URL                  (default: https://github.com/spring-projects/spring-petclinic.git)
#   REF        commit/tag/branch to check out     (default: main)
set -euo pipefail

WORK_DIR="${WORK_DIR:-/tmp/mat-calibration}"
REPO_URL="${REPO_URL:-https://github.com/spring-projects/spring-petclinic.git}"
REF="${REF:-main}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROFILE_SRC="${SCRIPT_DIR}/EnterpriseMemoryProfile.java"

if [[ ! -f "$PROFILE_SRC" ]]; then
    echo "missing $PROFILE_SRC" >&2
    exit 1
fi

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

if [[ ! -d spring-petclinic ]]; then
    echo "[setup] cloning $REPO_URL"
    git clone --depth 50 "$REPO_URL"
fi

cd spring-petclinic
git fetch --depth 50 origin "$REF"
git checkout "$REF"

DEST_DIR="src/main/java/org/springframework/samples/petclinic/memprofile"
mkdir -p "$DEST_DIR"
cp "$PROFILE_SRC" "$DEST_DIR/EnterpriseMemoryProfile.java"

echo "[setup] building (this can take a few minutes on first run)"
./mvnw -q -DskipTests package

JAR="$(ls target/spring-petclinic-*.jar | grep -v sources | head -n1)"
echo "[setup] built: $WORK_DIR/spring-petclinic/$JAR"
