#!/usr/bin/env bash
# Run the observer-effect benchmark suite.
#
# Full suite (all scenarios, plain vs. capture comparison):
#   ./run.sh
#
# Single scenario, specific mode:
#   ./run.sh <ScenarioClass> [plain|capture]
#   ./run.sh observer.ScenarioStoreBuf plain
#   ./run.sh observer.ScenarioDekker capture
#   ./run.sh observer.ScenarioMessagePass plain
#
# The capture agent is located automatically from:
#   1. <repo-root>/libs/           (pre-built, no Maven required)
#   2. <repo-root>/capture/target/ (Maven build output)
#
# NOTE: The agent JAR must include the exclude= argument support added in
# instr/Agent.java.  If it was built before that change, rebuild with:
#   mvn package -DskipTests  (from the repo root)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
RUNNER_JAR="$SCRIPT_DIR/target/jcstress.jar"
JCSTRESS_JAR="${JCSTRESS_JAR:-$RUNNER_JAR}"

# ── Locate capture agent JAR ───────────────────────────────────────────────────
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

# ── Ensure local runner jar exists ─────────────────────────────────────────────
if [ ! -f "$RUNNER_JAR" ]; then
    echo "target/jcstress.jar not found — building local observer suite..."
    "$SCRIPT_DIR/build.sh"
    echo
fi

# ── Validate selected target jcstress jar ─────────────────────────────────────
if [ ! -f "$JCSTRESS_JAR" ]; then
    echo "ERROR: JCSTRESS_JAR does not exist: $JCSTRESS_JAR" >&2
    exit 1
fi

# ── Single-scenario mode ───────────────────────────────────────────────────────
if [ $# -ge 1 ]; then
    SCENARIO="$1"
    MODE="${2:-plain}"

    case "$MODE" in
        plain)
            java -jar "$JCSTRESS_JAR" -t "$SCENARIO" -time 5
            ;;
        capture)
            java -jar "$JCSTRESS_JAR" -t "$SCENARIO" -time 5 \
                 -jvmArgs "-javaagent:$CAPTURE_JAR=exclude=org/openjdk/jcstress"
            ;;
        *)
            echo "Unknown mode '$MODE'. Valid modes: plain | capture" >&2
            exit 1
            ;;
    esac
    exit 0
fi

# ── Full suite ─────────────────────────────────────────────────────────────────
java -cp "$RUNNER_JAR" observer.ObserverEffectRunner \
    "$CAPTURE_JAR" "$JCSTRESS_JAR"
