#!/usr/bin/env bash
# Compiles all overhead workload sources and packages them into overhead-bench.jar.
# Requires JDK 11+.  No Maven needed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Compiling sources..."
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out"

find "$SCRIPT_DIR/src" -name "*.java" -print0 \
    | xargs -0 javac --release 11 -d "$SCRIPT_DIR/out"

echo "Packaging..."
jar cf "$SCRIPT_DIR/overhead-bench.jar" -C "$SCRIPT_DIR/out" .

echo "Built: $SCRIPT_DIR/overhead-bench.jar"
