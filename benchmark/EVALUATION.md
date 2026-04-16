# Running the Capture-and-Replay Agents on the Benchmark Suites

This document explains how to run the capture and replay agents against the
benchmark test suites included in this artifact.

## Overview

The benchmark contains six test suites:

| Suite | Type | Test cases | Entry point |
|-------|------|-----------|-------------|
| **SCTBench** | Synthetic concurrency bugs | 28 | `main()` method |
| **JaConTeBe** | Java concurrency bugs | 24 | `main()` method |
| **Kafka** | Real-world (Apache Kafka Streams) | 12 | JUnit 5 |
| **Lucene** | Real-world (Apache Lucene) | 4 | JUnit 5 |
| **Guava** | Real-world (Google Guava) | 2 | JUnit 4 |
| **Lincheck** | Concurrent data structures | 9 | JUnit 5 |

The capture agent records a synchronisation trace to `trace.bin`. The replay
agent reads that file and re-executes the same interleaving. A successful replay
of a buggy run will reproduce the original failure.

---

## Prerequisites

### 1. Build the capture-and-replay agents

From the root of the `capture_and_replay` project:

```bash
mvn package
```

This produces:
- `capture/target/trace-capture-agent.jar`
- `replay/target/trace-replay-agent.jar`

Set a convenience variable (used in all commands below):

```bash
export AGENT_ROOT=/path/to/capture_and_replay
export CAPTURE_AGENT=$AGENT_ROOT/capture/target/trace-capture-agent.jar
export REPLAY_AGENT=$AGENT_ROOT/replay/target/trace-replay-agent.jar
```

### 2. Build the benchmark applications

The benchmark uses Python for orchestration. Install dependencies with `uv`
(or `pip`):

```bash
cd benchmark
uv sync          # or: pip install -e .
```

Then build each suite you want to test. Each suite downloads or compiles its
own JARs:

```bash
python3 -m fray_benchmark build sctbench
python3 -m fray_benchmark build jacontebe
python3 -m fray_benchmark build kafka
python3 -m fray_benchmark build lucene
python3 -m fray_benchmark build guava
python3 -m fray_benchmark build lincheck
```

Kafka, Lucene, and Guava require Gradle and JDK 21. JaConTeBe requires its
`scripts/install.sh` to compile individual test cases — this pulls in older
library versions.

---

## Capture-and-Replay Workflow

For every test case the workflow is:

1. **Capture** — run the program once (or many times until a bug manifests).
   The agent writes `trace.bin` to the working directory.
2. **Replay** — run the program again with the replay agent. It reads
   `trace.bin` and enforces the recorded interleaving.
3. **Check** — a non-zero exit code (assertion error, exception) means the
   bug was reproduced.

---

## Running SCTBench

SCTBench tests are plain `main()` classes compiled into a single JAR.

### Classpath

```bash
SCTBENCH_JAR=bms/SCTBench/build/libs/SCTBench.jar   # exact name may vary
```

Verify with:

```bash
ls bms/SCTBench/build/libs/
```

### Running one test case

Replace `<ClassName>` with a fully-qualified class from `fray_benchmark/assets/sctbench.txt`
(e.g. `cmu.pasta.fray.benchmark.sctbench.cs.origin.AccountBad`).

```bash
# Capture
java -ea \
  -javaagent:$CAPTURE_AGENT \
  -cp $SCTBENCH_JAR \
  <ClassName>

# Replay
java -ea \
  -javaagent:$REPLAY_AGENT \
  -cp $SCTBENCH_JAR \
  <ClassName>
```

`trace.bin` is written to and read from the current directory. Run both
commands from the same directory.

### Running all SCTBench test cases

```bash
#!/usr/bin/env bash
set -euo pipefail

SCTBENCH_JAR=bms/SCTBench/build/libs/SCTBench.jar
OUT=output/capture-replay/sctbench
mkdir -p $OUT

while IFS= read -r class; do
  [ -z "$class" ] && continue
  dir="$OUT/${class##*.}"   # use simple class name as directory
  mkdir -p "$dir"

  echo "=== $class ==="

  # Capture
  java -ea \
    -javaagent:$CAPTURE_AGENT \
    -cp $SCTBENCH_JAR \
    "$class" \
    > "$dir/capture.stdout" 2> "$dir/capture.stderr"
  capture_rc=$?

  cp trace.bin "$dir/trace.bin"

  # Replay
  java -ea \
    -javaagent:$REPLAY_AGENT \
    -cp $SCTBENCH_JAR \
    "$class" \
    > "$dir/replay.stdout" 2> "$dir/replay.stderr"
  replay_rc=$?

  # Record outcome
  if [ $replay_rc -ne 0 ]; then
    echo "BUG REPRODUCED (exit $replay_rc)" | tee "$dir/result.txt"
  else
    echo "No error on replay"                | tee "$dir/result.txt"
  fi

done < fray_benchmark/assets/sctbench.txt
```

---

## Running JaConTeBe

JaConTeBe tests also use `main()` methods but each test case is compiled into
its own directory under `bms/JaConTeBe/build/<testname>/`.

The classpath for each test case is read from its `.jpf` configuration file.
Use the Python framework to print the commands:

```python
# print_jacontebe.py
from fray_benchmark.bm_configs.jacontebe import JaConTeBe

bm = JaConTeBe()
for tc in bm.get_test_cases("java"):
    cp = ":".join(tc.executor.classpaths)
    args = " ".join(tc.executor.args)
    print(f"CLASS={tc.executor.clazz}")
    print(f"CP={cp}")
    print(f"ARGS={args}")
    print()
```

```bash
python3 print_jacontebe.py
```

Then run capture and replay using the classpath and class printed for each
test case:

```bash
# Capture
java -ea \
  -javaagent:$CAPTURE_AGENT \
  -cp <CP> \
  <CLASS> [ARGS]

# Replay
java -ea \
  -javaagent:$REPLAY_AGENT \
  -cp <CP> \
  <CLASS> [ARGS]
```

---

## Running JUnit-Based Suites (Kafka, Lucene, Guava, Lincheck)

JUnit tests are wrapped by `helpers/junit-runner`, which the benchmark
framework adds to the classpath automatically. The entry class is always
`org.pastalab.fray.helpers.JUnitRunner` and the test is identified by
`<FullyQualifiedClass>#<methodName>`.

First build the runner:

```bash
cd helpers/junit-runner
./gradlew jar copyDependencies
cd ../..
```

### Classpath structure

```
RUNNER_CP=helpers/junit-runner/build/libs/junit-runner-1.0-SNAPSHOT.jar:helpers/junit-runner/build/dependency/*
```

For each suite append the suite-specific JARs.

**Kafka:**
```bash
KAFKA_HOME=bms/kafka
KAFKA_CP=$KAFKA_HOME/streams/build/classes/java/main:\
$KAFKA_HOME/streams/build/classes/java/test:\
$KAFKA_HOME/streams/build/resources/test:\
$KAFKA_HOME/streams/build/resources/main:\
$KAFKA_HOME/streams/build/dependency/*
```

**Lucene:**
```bash
LUCENE_HOME=bms/lucene
LUCENE_CP=$(ls $LUCENE_HOME/lucene/build/packages/*.jar | tr '\n' ':')
# See fray_benchmark/bm_configs/lucene.py for the exact glob patterns.
```

**Guava** (JUnit 4):
```bash
GUAVA_HOME=bms/guava
GUAVA_CP=$GUAVA_HOME/guava-tests/build/dependency/*:$GUAVA_HOME/guava/build/dependency/*
```

**Lincheck:**
```bash
LINCHECK_HOME=bms/licheck
LINCHECK_CP=$LINCHECK_HOME/lincheck/build/dependency/*
```

### Running one JUnit test case

```bash
TEST=org.apache.kafka.streams.KafkaStreamsTest#shouldReturnFalseOnCloseWhenThreadsHaventTerminated

# Capture
java -ea \
  -javaagent:$CAPTURE_AGENT \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED \
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  -cp "$RUNNER_CP:$KAFKA_CP" \
  org.pastalab.fray.helpers.JUnitRunner \
  junit5 \
  "$TEST"

# Replay
java -ea \
  -javaagent:$REPLAY_AGENT \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED \
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  -cp "$RUNNER_CP:$KAFKA_CP" \
  org.pastalab.fray.helpers.JUnitRunner \
  junit5 \
  "$TEST"
```

Use `junit4` instead of `junit5` for Guava.

### Printing all JUnit commands via the framework

```python
# print_junit_commands.py
import os
from fray_benchmark.bm_configs.kafka import KafkaBenchmark   # or Lucene/Guava/Lincheck

CAPTURE_AGENT = os.environ["CAPTURE_AGENT"]
ADDS = [
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.util=ALL-UNNAMED",
    "--add-opens", "java.base/java.io=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
]

bm = KafkaBenchmark()
for tc in bm.get_test_cases("java"):
    cp = ":".join(tc.executor.classpaths)
    args = tc.executor.args   # ["junit5", "TestClass#method"]
    cmd = ["java", "-ea", f"-javaagent:{CAPTURE_AGENT}", *ADDS, "-cp", cp,
           tc.executor.clazz, *args]
    print(" ".join(cmd))
    print()
```

---

## Multiple Capture Attempts

Because concurrency bugs are timing-dependent, a single capture run may not
trigger the bug. Run the capture step in a loop and check the exit code or
output to detect whether the buggy interleaving occurred before replaying:

```bash
for i in $(seq 1 50); do
  java -ea -javaagent:$CAPTURE_AGENT -cp $SCTBENCH_JAR "$class" \
    > capture.stdout 2> capture.stderr
  rc=$?
  if [ $rc -ne 0 ]; then
    echo "Bug captured on attempt $i"
    cp trace.bin "trace_bug.bin"
    break
  fi
done
```

Then replay from the saved trace:

```bash
cp trace_bug.bin trace.bin
java -ea -javaagent:$REPLAY_AGENT -cp $SCTBENCH_JAR "$class"
```

---

## Output Conventions

| File | Contents |
|------|----------|
| `trace.bin` | Binary trace written by capture agent, read by replay agent |
| `capture.stdout` / `capture.stderr` | Output of the capture run |
| `replay.stdout` / `replay.stderr` | Output of the replay run |
| `result.txt` | `BUG REPRODUCED` or `No error on replay` |

A non-zero exit code from the replay run indicates the bug was reproduced.
For assertion-based bugs the JVM exits with code 1. For deadlocks or hangs
wrap the command with `timeout`:

```bash
timeout 30 java -ea -javaagent:$REPLAY_AGENT -cp $CP $CLASS
```

---

## Suggested Evaluation Order

1. **SCTBench** — start here. Tests are self-contained `main()` classes with
   well-known bugs and fast execution times.
2. **JaConTeBe** — similar structure; slightly more complex classpath setup.
3. **Kafka** — real-world JUnit suite; good coverage of production concurrency
   patterns.
4. **Lucene / Guava / Lincheck** — additional real-world suites once the
   above are working.
