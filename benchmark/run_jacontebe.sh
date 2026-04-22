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

# JaConTeBe uses old libraries (Mockito 1.9.5 / CGLib) that require Java 11.
# The agents are compiled for Java 11 (class file version 55).
JAVA=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home/bin/java
RUN_TIMEOUT=${JACONTEBE_TIMEOUT:-45s}
mkdir -p $OUT

# Returns true if any recognized concurrency bug signal appears in the output files.
# Covers signals from both the JaConTeBe harness and our capture/replay agents.
has_bug_signal() {
  local stdout_file="$1"
  local stderr_file="$2"

  grep -Eqs \
    "AssertionError|\
Bug [Ff]ound!|\
Finished test: Bug has been reproduced successfully|\
Deadlock detected|\
RuntimeException: deadlock|\
Detected suspicious forever waiting|\
Program has been forced to exit from" \
    "$stdout_file" "$stderr_file"
}

bug_reason() {
  local stdout_file="$1"
  local stderr_file="$2"

  if grep -Eq "Finished test: Bug has been reproduced successfully" "$stdout_file" "$stderr_file"; then
    echo "bug-success"
  elif grep -q "AssertionError" "$stderr_file"; then
    echo "assertion"
  elif grep -Eq "Bug [Ff]ound!" "$stdout_file" "$stderr_file"; then
    echo "bug-message"
  elif grep -Eq "Program has been forced to exit from endless loop" "$stdout_file" "$stderr_file"; then
    echo "endless-loop"
  elif grep -Eq "Program has been forced to exit from forever waiting|Detected suspicious forever waiting" "$stdout_file" "$stderr_file"; then
    echo "forever-waiting"
  elif grep -Eq "Program has been forced to exit from deadlock|Deadlock detected|RuntimeException: deadlock" "$stdout_file" "$stderr_file"; then
    echo "deadlock"
  else
    echo "bug-signal"
  fi
}

has_infra_error() {
  local stdout_file="$1"
  local stderr_file="$2"

  grep -Eqs \
    "VerifyError|\
ClassFormatError|\
UnsupportedClassVersionError|\
NoClassDefFoundError|\
NoSuchMethodError|\
IllegalAccessError|\
LinkageError|\
Unable to initialize main class|\
Deterministic site id collision|\
\[Agent\] Fatal error" \
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

  # Parse target and classpath from .jpf file (strip \r for CRLF files)
  target=$(grep '^target' "$jpf_file" | tr -d '\r' | sed 's/target *= *//')
  raw_cp=$(grep '^classpath' "$jpf_file" | tr -d '\r' | sed 's/classpath *= *//')

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
  $TIMEOUT_CMD "$RUN_TIMEOUT" $JAVA -ea \
    -javaagent:$CAPTURE_AGENT \
    -cp "$classpath" \
    "$target" \
    > "$dir/capture.stdout" 2> "$dir/capture.stderr" || capture_rc=$?

  capture_bug=false
  capture_bug_reason=""
  if has_bug_signal "$dir/capture.stdout" "$dir/capture.stderr"; then
    capture_bug=true
    capture_bug_reason=$(bug_reason "$dir/capture.stdout" "$dir/capture.stderr")
  fi

  if [ $capture_rc -eq 124 ]; then
    if [ "$capture_bug" = true ]; then
      : # timed out but bug was already triggered — proceed to replay
    elif has_infra_error "$dir/capture.stdout" "$dir/capture.stderr"; then
      echo "CAPTURE ERROR (instrumentation/runtime error before timeout) — $bench" | tee "$dir/result.txt"
      continue
    elif [ -f trace.bin ]; then
      # Timed out with no explicit bug signal but trace.bin was written — the
      # program froze (likely deadlocked) before the watchdog could print its
      # signal.  Proceed to replay: if replay reproduces a bug signal the
      # capture did capture the bug; otherwise record as timeout.
      capture_bug=true
      capture_bug_reason="timeout-with-trace"
    else
      echo "TIMEOUT (capture) — $bench" | tee "$dir/result.txt"
      continue
    fi
  elif [ $capture_rc -ne 0 ]; then
    if [ "$capture_bug" != true ]; then
      echo "CAPTURE ERROR (exit $capture_rc with no recognized bug signal) — $bench" | tee "$dir/result.txt"
      continue
    fi
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
  $TIMEOUT_CMD "$RUN_TIMEOUT" $JAVA -ea \
    -javaagent:$REPLAY_AGENT \
    -cp "$classpath" \
    "$target" \
    > "$dir/replay.stdout" 2> "$dir/replay.stderr" || replay_rc=$?

  replay_bug=false
  replay_bug_reason=""
  if has_bug_signal "$dir/replay.stdout" "$dir/replay.stderr"; then
    replay_bug=true
    replay_bug_reason=$(bug_reason "$dir/replay.stdout" "$dir/replay.stderr")
  fi

  # Record outcome
  if [ "$replay_bug" = true ]; then
    if [ "$capture_bug" = true ]; then
      echo "BUG REPRODUCED ($replay_bug_reason) — $bench" | tee "$dir/result.txt"
    else
      echo "UNEXPECTED BUG SIGNAL on replay ($replay_bug_reason) — $bench" | tee "$dir/result.txt"
    fi
  elif [ $replay_rc -eq 124 ]; then
    if has_infra_error "$dir/replay.stdout" "$dir/replay.stderr"; then
      echo "REPLAY ERROR (instrumentation/runtime error before timeout) — $bench" | tee "$dir/result.txt"
    else
      echo "TIMEOUT (replay) — $bench" | tee "$dir/result.txt"
    fi
  elif [ $replay_rc -ne 0 ]; then
    if has_infra_error "$dir/replay.stdout" "$dir/replay.stderr"; then
      echo "REPLAY ERROR (instrumentation/runtime error) — $bench" | tee "$dir/result.txt"
    else
      echo "REPLAY ERROR (exit $replay_rc with no recognized bug signal) — $bench" | tee "$dir/result.txt"
    fi
  else
    if [ "$capture_bug_reason" = "timeout-with-trace" ]; then
      # Replay completed cleanly — the trace didn't reproduce the bug, meaning
      # the capture timeout was a genuine timeout, not a captured deadlock.
      echo "TIMEOUT (capture) — $bench" | tee "$dir/result.txt"
    elif [ "$capture_bug" = true ]; then
      echo "BUG NOT REPRODUCED — $bench" | tee "$dir/result.txt"
    else
      echo "No bug triggered — $bench" | tee "$dir/result.txt"
    fi
  fi

done < fray_benchmark/assets/jacontebe.txt
