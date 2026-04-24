#!/usr/bin/env bash
set -uo pipefail

TIMEOUT_CMD=$(command -v gtimeout || command -v timeout || true)
STDBUF_CMD=$(command -v stdbuf || true)
if [ -z "$TIMEOUT_CMD" ]; then
  echo "ERROR: neither gtimeout nor timeout found. Install with: brew install coreutils"
  exit 1
fi

SCTBENCH_JAR=bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar
OUT=output/capture-replay/sctbench
CAPTURE_AGENT=../capture/target/trace-capture-agent.jar
REPLAY_AGENT=../replay/target/trace-replay-agent.jar
JAVA_CMD=${JAVA_CMD:-java}
MAX_ATTEMPTS=${MAX_ATTEMPTS:-10}
CAPTURE_TIMEOUT=${CAPTURE_TIMEOUT:-3s}
REPLAY_TIMEOUT=${REPLAY_TIMEOUT:-5s}
if "$JAVA_CMD" -version >/dev/null 2>&1; then
  java_major=$("$JAVA_CMD" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2; exit}')
  if [ -n "${java_major:-}" ] && [ "$java_major" -lt 21 ] 2>/dev/null; then
    for candidate in /opt/homebrew/opt/openjdk/bin/java /opt/homebrew/opt/openjdk@21/bin/java; do
      if [ -x "$candidate" ]; then
        JAVA_CMD="$candidate"
        break
      fi
    done
  fi
fi
OPEN_FLAGS=(
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens java.base/java.util.concurrent.locks=ALL-UNNAMED
)
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

has_divergence_signal() {
  local stdout_file="$1"
  local stderr_file="$2"

  grep -Eqs "\\[DIVERGENCE\\]|Replay has structurally diverged|valued event mismatch|site mismatch|object mismatch" \
    "$stdout_file" "$stderr_file"
}

has_verify_error() {
  local stdout_file="$1"
  local stderr_file="$2"

  grep -Eqs "VerifyError|Bad type on operand stack|Inconsistent stackmap frames" \
    "$stdout_file" "$stderr_file"
}

run_timed_java() {
  local timeout_value="$1"
  shift

  if [ -n "$STDBUF_CMD" ]; then
    "$TIMEOUT_CMD" --foreground --kill-after=1s "$timeout_value" \
      "$STDBUF_CMD" -oL -eL "$@"
  else
    "$TIMEOUT_CMD" --foreground --kill-after=1s "$timeout_value" "$@"
  fi
}

while IFS= read -r class; do
  [ -z "$class" ] && continue
  dir="$OUT/${class##*.}"   # use simple class name as directory
  mkdir -p "$dir"

  echo "=== $class ==="
  final_message=""

  for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
    attempt_prefix="$dir/attempt${attempt}"

    rm -f trace.bin
    capture_rc=0
    run_timed_java "$CAPTURE_TIMEOUT" "$JAVA_CMD" -ea \
      -javaagent:$CAPTURE_AGENT \
      "${OPEN_FLAGS[@]}" \
      -cp $SCTBENCH_JAR \
      "$class" \
      > "${attempt_prefix}.capture.stdout" 2> "${attempt_prefix}.capture.stderr" || capture_rc=$?

    capture_bug=false
    capture_bug_reason=""
    if has_bug_signal "${attempt_prefix}.capture.stdout" "${attempt_prefix}.capture.stderr"; then
      capture_bug=true
      if grep -q "AssertionError" "${attempt_prefix}.capture.stderr"; then
        capture_bug_reason="assertion"
      elif grep -Eq "Bug [Ff]ound!" "${attempt_prefix}.capture.stdout" "${attempt_prefix}.capture.stderr"; then
        capture_bug_reason="bug-message"
      else
        capture_bug_reason="deadlock-signal"
      fi
    elif [ $capture_rc -eq 124 ] && is_deadlock_benchmark "$class"; then
      capture_bug=true
      capture_bug_reason="timeout"
    fi

    if [ $capture_rc -eq 124 ] && [ "$capture_bug" != true ]; then
      if has_divergence_signal "${attempt_prefix}.capture.stdout" "${attempt_prefix}.capture.stderr"; then
        final_message="CAPTURE DIVERGED then TIMED OUT after $attempt attempt(s) — $class"
      else
        final_message="TIMEOUT (capture) after $attempt attempt(s) — $class"
      fi
      break
    fi
    if [ $capture_rc -ne 0 ] && [ "$capture_bug" != true ]; then
      if has_verify_error "${attempt_prefix}.capture.stdout" "${attempt_prefix}.capture.stderr"; then
        final_message="CAPTURE VERIFY ERROR (exit $capture_rc) on attempt $attempt — $class"
      elif has_divergence_signal "${attempt_prefix}.capture.stdout" "${attempt_prefix}.capture.stderr"; then
        final_message="CAPTURE DIVERGED (exit $capture_rc) on attempt $attempt — $class"
      else
        final_message="CAPTURE ERROR (exit $capture_rc with no recognized bug signal) on attempt $attempt — $class"
      fi
      break
    fi

    if [ "$capture_bug" != true ]; then
      final_message="No bug triggered after $attempt attempt(s) — $class"
      continue
    fi

    if [ -f trace.bin ]; then
      cp trace.bin "${attempt_prefix}.trace.bin"
      cp "${attempt_prefix}.capture.stdout" "$dir/capture.stdout"
      cp "${attempt_prefix}.capture.stderr" "$dir/capture.stderr"
      cp "${attempt_prefix}.trace.bin" "$dir/trace.bin"
    else
      final_message="BUG TRIGGERED IN CAPTURE ($capture_bug_reason), but trace.bin was not produced; replay skipped — $class"
      break
    fi

    replay_rc=0
    run_timed_java "$REPLAY_TIMEOUT" "$JAVA_CMD" -ea \
      -javaagent:$REPLAY_AGENT \
      "${OPEN_FLAGS[@]}" \
      -cp $SCTBENCH_JAR \
      "$class" \
      > "${attempt_prefix}.replay.stdout" 2> "${attempt_prefix}.replay.stderr" || replay_rc=$?

    replay_bug=false
    replay_bug_reason=""
    replay_diverged=false
    if has_bug_signal "${attempt_prefix}.replay.stdout" "${attempt_prefix}.replay.stderr"; then
      replay_bug=true
      if grep -q "AssertionError" "${attempt_prefix}.replay.stderr"; then
        replay_bug_reason="assertion"
      elif grep -Eq "Bug [Ff]ound!" "${attempt_prefix}.replay.stdout" "${attempt_prefix}.replay.stderr"; then
        replay_bug_reason="bug-message"
      else
        replay_bug_reason="deadlock-signal"
      fi
    elif [ $replay_rc -eq 124 ] && is_deadlock_benchmark "$class"; then
      replay_bug=true
      replay_bug_reason="timeout"
    fi
    if has_divergence_signal "${attempt_prefix}.replay.stdout" "${attempt_prefix}.replay.stderr"; then
      replay_diverged=true
    fi

    cp "${attempt_prefix}.replay.stdout" "$dir/replay.stdout"
    cp "${attempt_prefix}.replay.stderr" "$dir/replay.stderr"

    if [ $replay_rc -eq 124 ]; then
      if [ "$replay_bug" = true ]; then
        final_message="BUG REPRODUCED ($replay_bug_reason; process timed out after signal) on attempt $attempt — $class"
        break
      fi
      if [ "$replay_diverged" = true ]; then
        final_message="REPLAY DIVERGED then TIMED OUT on attempt $attempt — $class"
      else
        final_message="TIMEOUT (replay) on attempt $attempt — $class"
      fi
      continue
    fi

    if [ "$replay_bug" = true ]; then
      final_message="BUG REPRODUCED ($replay_bug_reason) on attempt $attempt — $class"
      break
    fi

    if [ $replay_rc -ne 0 ]; then
      if [ "$replay_diverged" = true ]; then
        final_message="REPLAY DIVERGED (exit $replay_rc) on attempt $attempt — $class"
      else
        final_message="REPLAY ERROR (exit $replay_rc with no recognized bug signal) on attempt $attempt — $class"
      fi
      continue
    fi

    if [ "$replay_diverged" = true ]; then
      final_message="REPLAY DIVERGED (exit 0) on attempt $attempt — $class"
    else
      final_message="BUG NOT REPRODUCED after attempt $attempt — $class"
    fi
  done

  if [ -z "$final_message" ]; then
    final_message="No bug triggered after $MAX_ATTEMPTS attempt(s) — $class"
  fi

  echo "$final_message" | tee "$dir/result.txt"

done < fray_benchmark/assets/sctbench.txt
