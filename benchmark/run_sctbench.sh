#!/usr/bin/env bash
set -uo pipefail

TIMEOUT_CMD=$(command -v gtimeout || command -v timeout || true)
if [ -z "$TIMEOUT_CMD" ]; then
  echo "ERROR: neither gtimeout nor timeout found. Install with: brew install coreutils"
  exit 1
fi

SCTBENCH_JAR=bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar
OUT=output/capture-replay/sctbench
CAPTURE_AGENT=../capture/target/trace-capture-agent.jar
REPLAY_AGENT=../replay/target/trace-replay-agent.jar
JAVA_CMD=${JAVA_CMD:-java}
mkdir -p $OUT

is_deadlock_benchmark() {
  case "$1" in
    cmu.pasta.fray.benchmark.sctbench.cs.origin.Carter01Bad|\
    cmu.pasta.fray.benchmark.sctbench.cs.origin.Deadlock01Bad|\
    cmu.pasta.fray.benchmark.sctbench.cs.origin.Phase01Bad|\
    cmu.pasta.fray.benchmark.sctbench.cs.origin.Sync01Bad|\
    cmu.pasta.fray.benchmark.sctbench.cs.origin.Sync02Bad)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

has_bug_signal() {
  local stdout_file="$1"
  local stderr_file="$2"

  grep -Eqs "AssertionError|Bug [Ff]ound!|Deadlock detected|RuntimeException: deadlock" \
    "$stdout_file" "$stderr_file"
}

while IFS= read -r class; do
  [ -z "$class" ] && continue
  dir="$OUT/${class##*.}"   # use simple class name as directory
  mkdir -p "$dir"

  echo "=== $class ==="

  # Capture
  rm -f trace.bin
  capture_rc=0
  $TIMEOUT_CMD 3s "$JAVA_CMD" -ea \
    -javaagent:$CAPTURE_AGENT \
    -cp $SCTBENCH_JAR \
    "$class" \
    > "$dir/capture.stdout" 2> "$dir/capture.stderr" || capture_rc=$?

  capture_bug=false
  capture_bug_reason=""
  if has_bug_signal "$dir/capture.stdout" "$dir/capture.stderr"; then
    capture_bug=true
    if grep -q "AssertionError" "$dir/capture.stderr"; then
      capture_bug_reason="assertion"
    elif grep -Eq "Bug [Ff]ound!" "$dir/capture.stdout" "$dir/capture.stderr"; then
      capture_bug_reason="bug-message"
    else
      capture_bug_reason="deadlock-signal"
    fi
  elif [ $capture_rc -eq 124 ] && is_deadlock_benchmark "$class"; then
    capture_bug=true
    capture_bug_reason="timeout"
  fi

  if [ $capture_rc -eq 124 ]; then
    if [ "$capture_bug" = true ]; then
      :
    else
      echo "TIMEOUT (capture) — $class" | tee "$dir/result.txt"
      continue
    fi
  elif [ $capture_rc -ne 0 ]; then
    if [ "$capture_bug" != true ]; then
      echo "CAPTURE ERROR (exit $capture_rc with no recognized bug signal) — $class" | tee "$dir/result.txt"
      continue
    fi
  fi

  if [ -f trace.bin ]; then
    cp trace.bin "$dir/trace.bin"
  elif [ "$capture_bug" = true ]; then
    echo "BUG TRIGGERED IN CAPTURE ($capture_bug_reason), but trace.bin was not produced; replay skipped — $class" | tee "$dir/result.txt"
    continue
  else
    echo "CAPTURE ERROR (trace.bin missing) — $class" | tee "$dir/result.txt"
    continue
  fi

  # Replay
  replay_rc=0
  $TIMEOUT_CMD 3s "$JAVA_CMD" -ea \
    -javaagent:$REPLAY_AGENT \
    -cp $SCTBENCH_JAR \
    "$class" \
    > "$dir/replay.stdout" 2> "$dir/replay.stderr" || replay_rc=$?

  replay_bug=false
  replay_bug_reason=""
  if has_bug_signal "$dir/replay.stdout" "$dir/replay.stderr"; then
    replay_bug=true
    if grep -q "AssertionError" "$dir/replay.stderr"; then
      replay_bug_reason="assertion"
    elif grep -Eq "Bug [Ff]ound!" "$dir/replay.stdout" "$dir/replay.stderr"; then
      replay_bug_reason="bug-message"
    else
      replay_bug_reason="deadlock-signal"
    fi
  elif [ $replay_rc -eq 124 ] && is_deadlock_benchmark "$class"; then
    replay_bug=true
    replay_bug_reason="timeout"
  fi

  # Record outcome
  if [ $replay_rc -eq 124 ]; then
    if [ "$replay_bug" = true ]; then
      if [ "$capture_bug" = true ]; then
        echo "BUG REPRODUCED ($replay_bug_reason) — $class" | tee "$dir/result.txt"
      else
        echo "UNEXPECTED BUG SIGNAL on replay ($replay_bug_reason) — $class" | tee "$dir/result.txt"
      fi
    else
      echo "TIMEOUT (replay) — $class" | tee "$dir/result.txt"
    fi
  elif [ "$replay_bug" = true ]; then
    if [ "$capture_bug" = true ]; then
      echo "BUG REPRODUCED ($replay_bug_reason) — $class" | tee "$dir/result.txt"
    else
      echo "UNEXPECTED BUG SIGNAL on replay ($replay_bug_reason) — $class" | tee "$dir/result.txt"
    fi
  elif [ $replay_rc -ne 0 ]; then
    echo "REPLAY ERROR (exit $replay_rc with no recognized bug signal) — $class" | tee "$dir/result.txt"
  else
    if [ "$capture_bug" = true ]; then
      echo "BUG NOT REPRODUCED — $class" | tee "$dir/result.txt"
    else
      echo "No bug triggered — $class" | tee "$dir/result.txt"
    fi
  fi

done < fray_benchmark/assets/sctbench.txt
