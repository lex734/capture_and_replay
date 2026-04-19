#!/usr/bin/env bash
set -uo pipefail

TIMEOUT_CMD=$(command -v gtimeout || command -v timeout || true)
if [ -z "$TIMEOUT_CMD" ]; then
  echo "ERROR: neither gtimeout nor timeout found. Install with: brew install coreutils"
  exit 1
fi

JACONTEBE_DIR=bms/JaConTeBe
JPF_SCRIPTS=$JACONTEBE_DIR/testplans.alt/jpfscripts
OUT=output/capture-replay/jacontebe
CAPTURE_AGENT=../capture/target/trace-capture-agent.jar
REPLAY_AGENT=../replay/target/trace-replay-agent.jar
mkdir -p $OUT

has_bug_signal() {
  local stdout_file="$1"
  local stderr_file="$2"

  grep -Eqs "AssertionError|Bug [Ff]ound!|Deadlock detected|RuntimeException: deadlock" \
    "$stdout_file" "$stderr_file"
}

while IFS= read -r bench; do
  [ -z "$bench" ] && continue

  jpf_file="$JPF_SCRIPTS/${bench}.jpf"
  if [ ! -f "$jpf_file" ]; then
    echo "SKIP (no jpf file) — $bench"
    continue
  fi

  build_dir="$JACONTEBE_DIR/build/$bench"
  if [ ! -d "$build_dir" ]; then
    echo "SKIP (no build dir) — $bench"
    continue
  fi

  # Parse target and classpath from .jpf file
  target=$(grep '^target' "$jpf_file" | sed 's/target *= *//')
  raw_cp=$(grep '^classpath' "$jpf_file" | sed 's/classpath *= *//')

  # Resolve ./source -> build dir, ./versions.alt -> lib dir (absolute paths)
  abs_build=$(cd "$build_dir" && pwd)
  abs_lib=$(cd "$JACONTEBE_DIR/versions.alt" && pwd)
  classpath=$(echo "$raw_cp" \
    | sed "s|\./source|$abs_build|g" \
    | sed "s|\./versions\.alt|$abs_lib|g")

  dir="$OUT/$bench"
  mkdir -p "$dir"

  echo "=== $bench ($target) ==="

  # Capture
  capture_rc=0
  $TIMEOUT_CMD 10s java -ea \
    -javaagent:$CAPTURE_AGENT \
    -cp "$classpath" \
    "$target" \
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
  fi

  if [ $capture_rc -eq 124 ]; then
    echo "TIMEOUT (capture) — $bench" | tee "$dir/result.txt"
    continue
  elif [ $capture_rc -ne 0 ] && [ "$capture_bug" != true ]; then
    echo "CAPTURE ERROR (exit $capture_rc with no recognized bug signal) — $bench" | tee "$dir/result.txt"
    continue
  fi

  if [ -f trace.bin ]; then
    cp trace.bin "$dir/trace.bin"
  elif [ "$capture_bug" = true ]; then
    echo "BUG TRIGGERED IN CAPTURE ($capture_bug_reason), but trace.bin was not produced; replay skipped — $bench" | tee "$dir/result.txt"
    continue
  else
    echo "CAPTURE ERROR (trace.bin missing) — $bench" | tee "$dir/result.txt"
    continue
  fi

  # Replay
  replay_rc=0
  $TIMEOUT_CMD 10s java -ea \
    -javaagent:$REPLAY_AGENT \
    -cp "$classpath" \
    "$target" \
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
  fi

  # Record outcome
  if [ $replay_rc -eq 124 ]; then
    echo "TIMEOUT (replay) — $bench" | tee "$dir/result.txt"
  elif [ "$replay_bug" = true ]; then
    if [ "$capture_bug" = true ]; then
      echo "BUG REPRODUCED ($replay_bug_reason) — $bench" | tee "$dir/result.txt"
    else
      echo "UNEXPECTED BUG SIGNAL on replay ($replay_bug_reason) — $bench" | tee "$dir/result.txt"
    fi
  elif [ $replay_rc -ne 0 ]; then
    echo "REPLAY ERROR (exit $replay_rc with no recognized bug signal) — $bench" | tee "$dir/result.txt"
  else
    if [ "$capture_bug" = true ]; then
      echo "BUG NOT REPRODUCED — $bench" | tee "$dir/result.txt"
    else
      echo "No bug triggered — $bench" | tee "$dir/result.txt"
    fi
  fi

done < fray_benchmark/assets/jacontebe.txt
