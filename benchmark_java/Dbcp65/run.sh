#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
JACONTEBE_LIB="$ROOT/benchmark/bms/JaConTeBe/versions.alt/lib/realLib"

CAPTURE_AGENT="$ROOT/capture/target/trace-capture-agent.jar"
REPLAY_AGENT="$ROOT/replay/target/trace-replay-agent.jar"
JDK11=${JDK11:-/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home}
JAVA=${JAVA:-$JDK11/bin/java}
JAVAC=${JAVAC:-$JDK11/bin/javac}
TIMEOUT_CMD=$(command -v gtimeout || command -v timeout || true)
RUN_TIMEOUT=${TIMEOUT:-30s}

CP=".:$JACONTEBE_LIB/commons-dbcp-1.2.jar:$JACONTEBE_LIB/commons-pool-1.2.jar:$JACONTEBE_LIB/mockito-all-1.9.5.jar:$JACONTEBE_LIB/commons-collections-2.1.jar"

OPEN_FLAGS=(
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.util=ALL-UNNAMED
  --add-opens java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED
)

cd "$SCRIPT_DIR"

# Compile sources before each run so replay never uses stale bytecode.
echo "[build] compiling Dbcp65..."
$JAVAC -cp "$CP" -sourcepath . \
  Dbcp65.java \
  org/apache/commons/dbcp/KeyGenerator.java

rm -f trace.bin

echo "=== Dbcp65: CAPTURE ==="
capture_rc=0
$TIMEOUT_CMD "$RUN_TIMEOUT" $JAVA -ea \
  -javaagent:"$CAPTURE_AGENT" \
  "${OPEN_FLAGS[@]}" \
  -cp "$CP" \
  Dbcp65 2>&1 | tee capture.log || capture_rc=$?

if grep -qE "Deadlock detected" capture.log; then
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
echo "=== Dbcp65: REPLAY ==="
cp trace.bin.capture trace.bin
replay_rc=0
$TIMEOUT_CMD "$RUN_TIMEOUT" $JAVA -ea \
  -javaagent:"$REPLAY_AGENT" \
  "${OPEN_FLAGS[@]}" \
  -cp "$CP" \
  Dbcp65 2>&1 | tee replay.log || replay_rc=$?

if grep -qE "Deadlock detected" replay.log; then
  echo "[replay] BUG REPRODUCED"
elif [ $replay_rc -eq 124 ]; then
  echo "[replay] TIMEOUT"
else
  echo "[replay] BUG NOT REPRODUCED (exit $replay_rc)"
fi
