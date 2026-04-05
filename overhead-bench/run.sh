#!/usr/bin/env bash
# Run the overhead benchmark suite.
#
# Full benchmark (all workloads, all modes):
#   ./run.sh
#
# Single workload, specific mode:
#   ./run.sh <WorkloadClass> [plain|capture|replay]
#   ./run.sh overhead.WorkloadPlainCounter plain
#   ./run.sh overhead.WorkloadAtomicCounter capture
#   ./run.sh overhead.WorkloadVolatileWrite replay
#
# Requires JDK 11+.  Agent JARs are located automatically from:
#   1. <repo-root>/libs/           (pre-built, no Maven required)
#   2. <repo-root>/capture/target/ and replay/target/  (Maven build output)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
BENCH_JAR="$SCRIPT_DIR/overhead-bench.jar"

# ── Locate agent JARs ──────────────────────────────────────────────────────────
find_jar() {
    local label="$1"; shift
    for candidate in "$@"; do
        if [ -f "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done
    echo "ERROR: $label not found. Checked:" >&2
    for candidate in "$@"; do echo "  $candidate" >&2; done
    echo "Run 'mvn package' from the repo root or ensure libs/ contains agent JARs." >&2
    exit 1
}

CAPTURE_JAR=$(find_jar "capture agent" \
    "$REPO_ROOT/libs/trace-capture-agent.jar" \
    "$REPO_ROOT/capture/target/trace-capture-agent.jar")

REPLAY_JAR=$(find_jar "replay agent" \
    "$REPO_ROOT/libs/trace-replay-agent.jar" \
    "$REPO_ROOT/replay/target/trace-replay-agent.jar")

# ── Build benchmark jar if missing ────────────────────────────────────────────
if [ ! -f "$BENCH_JAR" ]; then
    echo "overhead-bench.jar not found — building..."
    "$SCRIPT_DIR/build.sh"
    echo
fi

# ── Single-workload mode ───────────────────────────────────────────────────────
if [ $# -ge 1 ]; then
    WORKLOAD="$1"
    MODE="${2:-plain}"

    case "$MODE" in
        plain)
            java -cp "$BENCH_JAR" "$WORKLOAD"
            ;;
        capture)
            java -javaagent:"$CAPTURE_JAR" -cp "$BENCH_JAR" "$WORKLOAD"
            ;;
        replay)
            echo "==> Capture phase (producing trace.bin in current directory)"
            java -javaagent:"$CAPTURE_JAR" -cp "$BENCH_JAR" "$WORKLOAD"
            echo "==> Replay phase"
            java -javaagent:"$REPLAY_JAR"  -cp "$BENCH_JAR" "$WORKLOAD"
            ;;
        *)
            echo "Unknown mode '$MODE'. Valid modes: plain | capture | replay" >&2
            exit 1
            ;;
    esac
    exit 0
fi

# ── Full benchmark ─────────────────────────────────────────────────────────────
java -cp "$BENCH_JAR" overhead.BenchmarkRunner \
    "$CAPTURE_JAR" "$REPLAY_JAR" "$BENCH_JAR"
