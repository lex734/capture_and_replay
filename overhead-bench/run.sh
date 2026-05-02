#!/usr/bin/env bash
# Run the overhead benchmark suite.
#
# Full benchmark (all workloads, all modes):
#   ./run.sh
# SCTBench suite in overhead mode:
#   ./run.sh --sctbench
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
SCTBENCH_JAR="$REPO_ROOT/benchmark/bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar"
SCTBENCH_SUITE="$REPO_ROOT/benchmark/fray_benchmark/assets/sctbench.txt"
SCTBENCH_OPEN_FLAGS=(
    --add-opens java.base/java.lang=ALL-UNNAMED
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED
    --add-opens java.base/java.util.concurrent.locks=ALL-UNNAMED
)

# Benchmark defaults. Override with environment variables if needed.
OVERHEAD_WARMUP="${OVERHEAD_WARMUP:-3}"
OVERHEAD_MEASURE="${OVERHEAD_MEASURE:-10}"
OVERHEAD_TIMEOUT_SECONDS="${OVERHEAD_TIMEOUT_SECONDS:-200}"
OVERHEAD_MEASURE_WINDOW_SECONDS="${OVERHEAD_MEASURE_WINDOW_SECONDS:-30}"

# SCTBench-specific defaults. Override with environment variables if needed.
SCTBENCH_WARMUP="${SCTBENCH_WARMUP:-3}"
SCTBENCH_MEASURE="${SCTBENCH_MEASURE:-20}"
SCTBENCH_TIMEOUT_SECONDS="${SCTBENCH_TIMEOUT_SECONDS:-8}"
SCTBENCH_MEASURE_WINDOW_SECONDS="${SCTBENCH_MEASURE_WINDOW_SECONDS:-30}"

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

CAPTURE_JAR="$REPO_ROOT/capture/target/trace-capture-agent.jar"

REPLAY_JAR="$REPO_ROOT/replay/target/trace-replay-agent.jar"

# ── Build benchmark jar if missing ────────────────────────────────────────────
if [ ! -f "$BENCH_JAR" ]; then
    echo "overhead-bench.jar not found — building..."
    "$SCRIPT_DIR/build.sh"
    echo
fi

# ── Single-workload mode ───────────────────────────────────────────────────────
if [ $# -ge 1 ] && [ "$1" = "--sctbench" ]; then
    if [ ! -f "$SCTBENCH_JAR" ]; then
        echo "ERROR: SCTBench jar not found at:" >&2
        echo "  $SCTBENCH_JAR" >&2
        echo "Build it first with:" >&2
        echo "  (cd \"$REPO_ROOT/benchmark/bms/SCTBench\" && ./gradlew jar)" >&2
        exit 1
    fi
    if [ ! -f "$SCTBENCH_SUITE" ]; then
        echo "ERROR: suite file not found: $SCTBENCH_SUITE" >&2
        exit 1
    fi

    java \
        -Doverhead.warmup="$SCTBENCH_WARMUP" \
        -Doverhead.measure="$SCTBENCH_MEASURE" \
        -Doverhead.timeout.seconds="$SCTBENCH_TIMEOUT_SECONDS" \
        -Doverhead.measure.window.seconds="$SCTBENCH_MEASURE_WINDOW_SECONDS" \
        -ea \
        -cp "$BENCH_JAR" overhead.BenchmarkRunner \
        "$CAPTURE_JAR" "$REPLAY_JAR" "$SCTBENCH_JAR" "$SCTBENCH_SUITE" \
        "${SCTBENCH_OPEN_FLAGS[@]}"
    exit 0
fi

if [ $# -ge 1 ]; then
    WORKLOAD="$1"
    MODE="${2:-plain}"

    case "$MODE" in
        plain)
            java -ea -cp "$BENCH_JAR" "$WORKLOAD"
            ;;
        capture)
            java -ea -javaagent:"$CAPTURE_JAR" -cp "$BENCH_JAR" "$WORKLOAD"
            ;;
        replay)
            echo "==> Capture phase (producing trace.bin in current directory)"
            java -ea -javaagent:"$CAPTURE_JAR" -cp "$BENCH_JAR" "$WORKLOAD"
            echo "==> Replay phase"
            java -ea -javaagent:"$REPLAY_JAR"  -cp "$BENCH_JAR" "$WORKLOAD"
            ;;
        *)
            echo "Unknown mode '$MODE'. Valid modes: plain | capture | replay" >&2
            exit 1
            ;;
    esac
    exit 0
fi

# ── Full benchmark ─────────────────────────────────────────────────────────────
java \
    -Doverhead.warmup="$OVERHEAD_WARMUP" \
    -Doverhead.measure="$OVERHEAD_MEASURE" \
    -Doverhead.timeout.seconds="$OVERHEAD_TIMEOUT_SECONDS" \
    -Doverhead.measure.window.seconds="$OVERHEAD_MEASURE_WINDOW_SECONDS" \
    -cp "$BENCH_JAR" overhead.BenchmarkRunner \
    "$CAPTURE_JAR" "$REPLAY_JAR" "$BENCH_JAR"
