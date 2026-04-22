#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)

CAPTURE_AGENT="$ROOT/capture/target/trace-capture-agent.jar"
REPLAY_AGENT="$ROOT/replay/target/trace-replay-agent.jar"
JAVA=${JAVA:-java}
TIMEOUT_CMD=$(command -v gtimeout || command -v timeout || true)
RUN_TIMEOUT=${TIMEOUT:-30s}

OPEN_FLAGS=(
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens java.base/java.util.concurrent.locks=ALL-UNNAMED
)

cd "$SCRIPT_DIR"

# Compile if needed
if [ ! -f Carter01Bad.class ]; then
  echo "[build] compiling Carter01Bad..."
  javac Carter01Bad.java
fi

rm -f trace.bin

echo "=== Carter01Bad: CAPTURE ==="
capture_rc=0
$TIMEOUT_CMD "$RUN_TIMEOUT" $JAVA -ea \
  -javaagent:"$CAPTURE_AGENT" \
  "${OPEN_FLAGS[@]}" \
  -cp . \
  Carter01Bad 2>&1 | tee capture.log || capture_rc=$?

if grep -qE "Deadlock detected|RuntimeException: deadlock" capture.log; then
  echo "[capture] Bug signal detected."
elif [ $capture_rc -eq 124 ]; then
  echo "[capture] Timed out (possible silent deadlock)."
else
  echo "[capture] No bug signal — try re-running (race is non-deterministic)."
  exit 0
fi

if [ ! -f trace.bin ]; then
  echo "[capture] trace.bin not produced — cannot replay."
  exit 1
fi

cp trace.bin trace.bin.capture

echo ""
echo "=== Carter01Bad: REPLAY ==="
cp trace.bin.capture trace.bin
replay_rc=0
$TIMEOUT_CMD "$RUN_TIMEOUT" $JAVA -ea \
  -javaagent:"$REPLAY_AGENT" \
  "${OPEN_FLAGS[@]}" \
  -cp . \
  Carter01Bad 2>&1 | tee replay.log || replay_rc=$?

if grep -qE "Deadlock detected|RuntimeException: deadlock" replay.log; then
  echo "[replay] BUG REPRODUCED"
elif [ $replay_rc -eq 124 ]; then
  echo "[replay] TIMEOUT"
else
  echo "[replay] BUG NOT REPRODUCED (exit $replay_rc)"
fi
