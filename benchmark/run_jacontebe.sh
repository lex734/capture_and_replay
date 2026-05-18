#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
SUBJECT_DIR="$REPO_ROOT/benchmark/bms/JaConTeBe"
EXPERIMENT_ROOT="$REPO_ROOT/benchmark/bms"
OUT_ROOT="$REPO_ROOT/benchmark/output/capture-replay/jacontebe"
CAPTURE_AGENT="$REPO_ROOT/capture/target/trace-capture-agent.jar"
REPLAY_AGENT="$REPO_ROOT/replay/target/trace-replay-agent.jar"
JAVA_CMD=${JAVA_CMD:-java}
CAPTURE_TIMEOUT=${CAPTURE_TIMEOUT:-20s}
REPLAY_TIMEOUT=${REPLAY_TIMEOUT:-20s}
TIMEOUT_KILL_AFTER=${TIMEOUT_KILL_AFTER:-5s}
KEEP_WORKDIR=${KEEP_WORKDIR:-0}
TIMEOUT_CMD=$(command -v gtimeout || command -v timeout || true)
STDBUF_CMD=$(command -v stdbuf || true)

ALL_TESTS=(
  dbcp1 dbcp2 dbcp3 dbcp4
  derby1 derby2 derby3 derby4 derby5
  groovy1 groovy2 groovy3 groovy4 groovy5 groovy6
  jdk6_1 jdk6_2 jdk6_3 jdk6_4 jdk6_5 jdk6_6 jdk6_7 jdk6_8 jdk6_9 jdk6_10 jdk6_11 jdk6_12 jdk6_13 jdk6_14
  jdk7_1 jdk7_2 jdk7_3 jdk7_4 jdk7_5 jdk7_6
  log4j1 log4j2 log4j3 log4j4 log4j5
  lucene1 lucene2
  pool1 pool2 pool3 pool4 pool5
)

usage() {
  cat <<'EOF'
Usage:
  benchmark/run_jacontebe.sh [test ...]
  benchmark/run_jacontebe.sh --list

Environment overrides:
  JAVA_CMD=<java>
  CAPTURE_TIMEOUT=<duration>   default: 20s
  REPLAY_TIMEOUT=<duration>    default: 20s
  TIMEOUT_KILL_AFTER=<duration> default: 5s
  KEEP_WORKDIR=1               keep per-test temp workdirs instead of deleting them

Runs each selected JaConTeBe test sequentially:
  1. stages sources with scripts/install.sh
  2. runs capture once with trace-capture-agent.jar
  3. runs replay once with trace-replay-agent.jar
  4. stores logs and artifacts under benchmark/output/capture-replay/jacontebe/<test>/
EOF
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  usage
  exit 0
fi

if [ "${1:-}" = "--list" ]; then
  printf '%s\n' "${ALL_TESTS[@]}"
  exit 0
fi

if [ ! -d "$SUBJECT_DIR" ]; then
  echo "ERROR: JaConTeBe directory not found at $SUBJECT_DIR"
  exit 1
fi

if [ ! -f "$CAPTURE_AGENT" ]; then
  echo "ERROR: capture agent not found at $CAPTURE_AGENT"
  exit 1
fi

if [ ! -f "$REPLAY_AGENT" ]; then
  echo "ERROR: replay agent not found at $REPLAY_AGENT"
  exit 1
fi

if [ -z "$TIMEOUT_CMD" ]; then
  echo "ERROR: neither gtimeout nor timeout is available"
  exit 1
fi

mkdir -p "$OUT_ROOT"
export experiment_root="$EXPERIMENT_ROOT"

run_timed() {
  local timeout_value="$1"
  shift

  if [ -n "$STDBUF_CMD" ]; then
    "$TIMEOUT_CMD" --foreground --kill-after="$TIMEOUT_KILL_AFTER" "$timeout_value" \
      "$STDBUF_CMD" -oL -eL "$@"
  else
    "$TIMEOUT_CMD" --foreground --kill-after="$TIMEOUT_KILL_AFTER" "$timeout_value" "$@"
  fi
}

runtime_cp() {
  local test_name="$1"
  printf './versions.alt/lib/%s.jar:./source' "$test_name"
}

cleanup_test_artifacts() {
  local test_name="$1"
  local work_dir="$2"

  case "$test_name" in
    derby1)
      rm -rf "$work_dir/DB"
      rm -f "$work_dir/derby.log"
      ;;
    groovy2)
      rm -rf "$work_dir/test"
      ;;
    jdk6_10)
      rm -f "$work_dir/file1" "$work_dir/file2"
      ;;
    jdk6_3|jdk7_3)
      rm -rf "$work_dir/classes"
      ;;
    jdk7_6)
      rm -rf "$work_dir/classes" "$work_dir/implcb"
      ;;
    lucene2)
      rm -rf "$work_dir"/TestDoug2*
      ;;
  esac
}

create_workdir() {
  local out_dir="$1"
  local test_name="$2"
  local work_dir

  work_dir=$(mktemp -d "/tmp/jacontebe.${test_name}.XXXXXX")
  ln -s "$SUBJECT_DIR/source" "$work_dir/source"
  ln -s "$SUBJECT_DIR/versions.alt" "$work_dir/versions.alt"
  printf '%s\n' "$work_dir"
}

main_and_args() {
  local test_name="$1"

  case "$test_name" in
    dbcp1) printf '%s\n' 'Dbcp65' ;;
    dbcp2) printf '%s\n' 'Dbcp270' ;;
    dbcp3) printf '%s\n' 'org.apache.commons.dbcp.datasources.Dbcp369' ;;
    dbcp4) printf '%s\n' 'org.apache.commons.dbcp.Dbcp271' ;;
    derby1) printf '%s\n' 'Derby4129' ;;
    derby2) printf '%s\n' 'Derby5560' ;;
    derby3) printf '%s\n' 'Derby5561' ;;
    derby4) printf '%s\n' 'org.junit.runner.JUnitCore org.apache.derby.impl.services.reflect.Derby764' ;;
    derby5) printf '%s\n' 'org.apache.derby.impl.store.raw.data.Derby5447' ;;
    groovy1) printf '%s\n' 'Groovy3495' ;;
    groovy2) printf '%s\n' 'Groovy4736' ;;
    groovy3) printf '%s\n' 'Groovy5198' ;;
    groovy4) printf '%s\n' 'groovy.servlet.Groovy6456' ;;
    groovy5) printf '%s\n' 'groovy.util.Groovy6068' ;;
    groovy6) printf '%s\n' 'org.codehaus.groovy.ast.Groovy4292' ;;
    jdk6_1) printf '%s\n' 'Test4243978' ;;
    jdk6_2) printf '%s\n' 'Test4742723' ;;
    jdk6_3) printf '%s\n' 'Test4779253' ;;
    jdk6_4) printf '%s\n' 'Test4813150' ;;
    jdk6_5) printf '%s\n' 'Test6436220' ;;
    jdk6_6) printf '%s\n' 'Test6492872' ;;
    jdk6_7) printf '%s\n' 'Test6582568' ;;
    jdk6_8) printf '%s\n' 'Test6588239' ;;
    jdk6_9) printf '%s\n' 'Test6648001' ;;
    jdk6_10) printf '%s\n' 'Test6927486' ;;
    jdk6_11) printf '%s\n' 'Test6934356' ;;
    jdk6_12) printf '%s\n' 'Test6977738' ;;
    jdk6_13) printf '%s\n' 'Test7100996' ;;
    jdk6_14) printf '%s\n' 'Test7132889' ;;
    jdk7_1) printf '%s\n' 'Test7045594' ;;
    jdk7_2) printf '%s\n' 'Test7122142' ;;
    jdk7_3) printf '%s\n' 'Test7132378' ;;
    jdk7_4) printf '%s\n' 'Test8010939' ;;
    jdk7_5) printf '%s\n' 'Test8012019' ;;
    jdk7_6) printf '%s\n' 'Test8023541' ;;
    log4j1) printf '%s\n' 'Test44032' ;;
    log4j2) printf '%s\n' 'com.main.Test41214' ;;
    log4j3) printf '%s\n' 'org.apache.log4j.helpers.Test54325' ;;
    log4j4) printf '%s\n' 'org.apache.log4j.Test38137' ;;
    log4j5) printf '%s\n' 'org.apache.log4j.Test50463' ;;
    lucene1) printf '%s\n' 'junit.textui.TestRunner org.apache.lucene.index.Test2783' ;;
    lucene2) printf '%s\n' 'junit.textui.TestRunner org.apache.lucene.Test1544' ;;
    pool1) printf '%s\n' 'Test120' ;;
    pool2) printf '%s\n' 'Test146' ;;
    pool3) printf '%s\n' 'Test149' ;;
    pool4) printf '%s\n' 'Test162' ;;
    pool5) printf '%s\n' 'org.apache.commons.pool.Test46' ;;
    *)
      echo "Unknown test: $test_name" >&2
      return 1
      ;;
  esac
}

special_jvm_opts() {
  local test_name="$1"

  case "$test_name" in
    jdk6_9)
      printf '%s\n' '-ea:sun.net.www.protocol.http.AuthenticationInfo -Dhttp.auth.serializeRequests=true'
      ;;
    jdk6_3|jdk7_3)
      printf '%s\n' '-Xbootclasspath/p:classes'
      ;;
    jdk7_6)
      printf '%s\n' '-Xbootclasspath/p:classes -Djava.security.policy=source/security.policy'
      ;;
    *)
      printf '\n'
      ;;
  esac
}

prepare_special_test() {
  local test_name="$1"
  local timeout_value="$2"
  local work_dir="$3"
  local classpath
  classpath=$(runtime_cp "$test_name")

  case "$test_name" in
    jdk6_3)
      (
        cd "$work_dir" || exit 1
        run_timed "$timeout_value" "$JAVA_CMD" -cp "$classpath" asm.LoggerModifier
      )
      ;;
    jdk7_3)
      (
        cd "$work_dir" || exit 1
        run_timed "$timeout_value" "$JAVA_CMD" -cp "$classpath" asm.FutureTaskModifier
      )
      ;;
    jdk7_6)
      mkdir -p "$work_dir/classes/edu/illinois/jacontebe/globalevent"
      cp "$SUBJECT_DIR/source/edu/illinois/jacontebe/globalevent/GlobalDriver.class" \
        "$work_dir/classes/edu/illinois/jacontebe/globalevent/GlobalDriver.class"
      (
        cd "$work_dir" || exit 1
        run_timed "$timeout_value" "$JAVA_CMD" -cp "$classpath" asm.ActivationModifier
      )
      ;;
  esac
}

run_one_mode() {
  local mode="$1"
  local test_name="$2"
  local out_dir="$3"
  local timeout_value="$4"
  local agent_jar="$5"
  local work_dir="$6"

  local cp
  cp=$(runtime_cp "$test_name")
  local main
  main=$(main_and_args "$test_name") || return 1
  local extra_opts
  extra_opts=$(special_jvm_opts "$test_name")
  local log_prefix="$out_dir/$mode"

  cleanup_test_artifacts "$test_name" "$work_dir"
  if ! prepare_special_test "$test_name" "$timeout_value" "$work_dir" >"$log_prefix.prep.stdout" 2>"$log_prefix.prep.stderr"; then
    cleanup_test_artifacts "$test_name" "$work_dir"
    return 98
  fi

  local rc=0

  if [ "$mode" = "capture" ]; then
    (
      cd "$work_dir" || exit 1
      rm -f trace.bin trace-boundaries.tsv schedule-boundaries.tsv trace-reduced.tsv trace-semantic.tsv
      # shellcheck disable=SC2086
      run_timed "$timeout_value" "$JAVA_CMD" $extra_opts \
        -javaagent:"$agent_jar" \
        -cp "$cp" \
        $main
    ) >"$log_prefix.stdout" 2>"$log_prefix.stderr" || rc=$?

    if [ -f "$work_dir/trace.bin" ]; then
      cp "$work_dir/trace.bin" "$out_dir/trace.bin"
    fi
    if [ -f "$work_dir/trace-boundaries.tsv" ]; then
      cp "$work_dir/trace-boundaries.tsv" "$out_dir/trace-boundaries.tsv"
    fi
  else
    (
      cd "$work_dir" || exit 1
      rm -f schedule-boundaries.tsv trace-reduced.tsv trace-semantic.tsv
      # shellcheck disable=SC2086
      run_timed "$timeout_value" "$JAVA_CMD" $extra_opts \
        -Dtool.static.analysis.path=./source \
        -javaagent:"$agent_jar" \
        -cp "$cp" \
        $main
    ) >"$log_prefix.stdout" 2>"$log_prefix.stderr" || rc=$?

    if [ -f "$work_dir/schedule-boundaries.tsv" ]; then
      cp "$work_dir/schedule-boundaries.tsv" "$out_dir/schedule-boundaries.tsv"
    fi
    if [ -f "$work_dir/trace-reduced.tsv" ]; then
      cp "$work_dir/trace-reduced.tsv" "$out_dir/trace-reduced.tsv"
    fi
    if [ -f "$work_dir/trace-semantic.tsv" ]; then
      cp "$work_dir/trace-semantic.tsv" "$out_dir/trace-semantic.tsv"
    fi
  fi

  cleanup_test_artifacts "$test_name" "$work_dir"
  return "$rc"
}

tests_to_run=()
if [ "$#" -eq 0 ]; then
  tests_to_run=("${ALL_TESTS[@]}")
else
  tests_to_run=("$@")
fi

summary_file="$OUT_ROOT/summary.tsv"
printf 'test\tstage_rc\tcapture_rc\treplay_rc\ttrace_present\tschedule_present\tresult\n' >"$summary_file"

echo "=== JaConTeBe Capture/Replay Runner ==="
echo "Subject dir    : $SUBJECT_DIR"
echo "Output dir     : $OUT_ROOT"
echo "Capture agent  : $CAPTURE_AGENT"
echo "Replay agent   : $REPLAY_AGENT"
echo "Capture timeout: $CAPTURE_TIMEOUT"
echo "Replay timeout : $REPLAY_TIMEOUT"
echo "Kill-after     : $TIMEOUT_KILL_AFTER"

total=0
stage_failures=0
capture_failures=0
replay_failures=0

for test_name in "${tests_to_run[@]}"; do
  total=$((total + 1))
  out_dir="$OUT_ROOT/$test_name"
  mkdir -p "$out_dir"

  echo
  echo "=== $test_name ==="

  stage_rc=0
  capture_rc=0
  replay_rc=0
  result="ok"
  work_dir=$(create_workdir "$out_dir" "$test_name")
  printf '%s\n' "$work_dir" >"$out_dir/workdir.txt"

  (
    cd "$SUBJECT_DIR" || exit 1
    ./scripts/install.sh orig "$test_name"
  ) >"$out_dir/install.stdout" 2>"$out_dir/install.stderr" || stage_rc=$?

  if [ "$stage_rc" -ne 0 ]; then
    stage_failures=$((stage_failures + 1))
    result="stage_failed"
    echo "STAGE FAILED (exit $stage_rc)"
    printf '%s\t%s\t-\t-\t%s\t%s\t%s\n' \
      "$test_name" "$stage_rc" "no" "no" "$result" >>"$summary_file"
    if [ "$KEEP_WORKDIR" != "1" ]; then
      rm -rf "$work_dir"
    fi
    continue
  fi

  run_one_mode capture "$test_name" "$out_dir" "$CAPTURE_TIMEOUT" "$CAPTURE_AGENT" "$work_dir" || capture_rc=$?
  if [ ! -f "$out_dir/trace.bin" ]; then
    capture_failures=$((capture_failures + 1))
    result="capture_missing_trace"
    if [ "$capture_rc" -eq 0 ]; then
      capture_rc=99
    fi
  else
    run_one_mode replay "$test_name" "$out_dir" "$REPLAY_TIMEOUT" "$REPLAY_AGENT" "$work_dir" || replay_rc=$?
    if [ "$capture_rc" -ne 0 ]; then
      capture_failures=$((capture_failures + 1))
      if [ "$replay_rc" -ne 0 ]; then
        replay_failures=$((replay_failures + 1))
        result="capture_and_replay_failed"
      else
        result="capture_failed_replay_ran"
      fi
    elif [ "$replay_rc" -ne 0 ]; then
      replay_failures=$((replay_failures + 1))
      result="replay_failed"
    fi
  fi

  trace_present="no"
  schedule_present="no"
  [ -f "$out_dir/trace.bin" ] && trace_present="yes"
  [ -f "$out_dir/schedule-boundaries.tsv" ] && schedule_present="yes"

  echo "stage=$stage_rc capture=$capture_rc replay=$replay_rc trace=$trace_present schedule=$schedule_present result=$result"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$test_name" "$stage_rc" "$capture_rc" "$replay_rc" "$trace_present" "$schedule_present" "$result" >>"$summary_file"
  if [ "$KEEP_WORKDIR" != "1" ]; then
    rm -rf "$work_dir"
  fi
done

echo
echo "===== SUMMARY ====="
echo "Tests selected : $total"
echo "Stage failures : $stage_failures"
echo "Capture issues : $capture_failures"
echo "Replay issues  : $replay_failures"
echo "Summary file   : $summary_file"
