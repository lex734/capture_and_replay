# JaConTeBe with the Capture/Replay Agents

This directory contains the JaConTeBe benchmark plus helper scripts that stage one bug at a time into `source/` and then run it against the matching support jar in `versions.alt/lib/`.

The capture/replay flow in this repo works well with JaConTeBe, but there is one important repo-specific detail:

- capture writes `trace.bin` in the current working directory
- replay reads `trace.bin` from the current working directory
- replay also runs mandatory static analysis
- for JaConTeBe, the staged test classes live in `./source`, so replay should be started with `-Dtool.static.analysis.path=./source`

## Prerequisites

From the repo root, make sure the agents are built:

```bash
mvn -q -pl common,capture,replay -am package -DskipTests
```

Set `experiment_root` to the parent directory of `JaConTeBe`:

```bash
export experiment_root=/home/enxing/capture_and_replay/benchmark/bms
```

Then move into the benchmark:

```bash
cd /home/enxing/capture_and_replay/benchmark/bms/JaConTeBe
```

## Basic Workflow

For a single JaConTeBe test `<test>`:

1. Stage and compile the test into `source/`.
2. Run the test once with the capture agent to produce `trace.bin`.
3. Run the same test again with the replay agent, reusing that `trace.bin`.

The staging step is:

```bash
./scripts/install.sh orig <test>
```

The runtime classpath for JaConTeBe is:

```bash
./versions.alt/lib/<test>.jar:./source
```

To run the whole suite sequentially with capture, replay, and per-test timeouts, use:

```bash
/home/enxing/capture_and_replay/benchmark/run_jacontebe.sh
```

Outputs go to `benchmark/output/capture-replay/jacontebe/`.

## Generic Commands

Replace:

- `<test>` with a JaConTeBe test name such as `log4j1` or `jdk7_1`
- `<main-and-args>` with that test's entry point, including any arguments
- `<extra-jvm-opts>` with any test-specific JVM options from `testplans.alt/testscript/<test>.sh`

Capture:

```bash
rm -f trace.bin schedule-boundaries.tsv
java <extra-jvm-opts> \
  -javaagent:/home/enxing/capture_and_replay/capture/target/trace-capture-agent.jar \
  -cp "./versions.alt/lib/<test>.jar:./source" \
  <main-and-args>
```

Replay:

```bash
java <extra-jvm-opts> \
  -Dtool.static.analysis.path=./source \
  -javaagent:/home/enxing/capture_and_replay/replay/target/trace-replay-agent.jar \
  -cp "./versions.alt/lib/<test>.jar:./source" \
  <main-and-args>
```

Artifacts produced in this directory:

- `trace.bin`: capture trace consumed by replay
- `schedule-boundaries.tsv`: distilled replay schedule written by replay

## Example: `log4j1`

Stage and compile:

```bash
./scripts/install.sh orig log4j1
```

Capture:

```bash
rm -f trace.bin schedule-boundaries.tsv
java \
  -javaagent:/home/enxing/capture_and_replay/capture/target/trace-capture-agent.jar \
  -cp "./versions.alt/lib/log4j1.jar:./source" \
  Test44032
```

Replay:

```bash
java \
  -Dtool.static.analysis.path=./source \
  -javaagent:/home/enxing/capture_and_replay/replay/target/trace-replay-agent.jar \
  -cp "./versions.alt/lib/log4j1.jar:./source" \
  Test44032
```

## Example: `jdk7_1`

Stage and compile:

```bash
./scripts/install.sh orig jdk7_1
```

Capture:

```bash
rm -f trace.bin schedule-boundaries.tsv
java \
  -javaagent:/home/enxing/capture_and_replay/capture/target/trace-capture-agent.jar \
  -cp "./versions.alt/lib/jdk7_1.jar:./source" \
  Test7045594
```

Replay:

```bash
java \
  -Dtool.static.analysis.path=./source \
  -javaagent:/home/enxing/capture_and_replay/replay/target/trace-replay-agent.jar \
  -cp "./versions.alt/lib/jdk7_1.jar:./source" \
  Test7045594
```

## Test Name to Main Class

Use these entry points with the generic commands above.

| Test | Main class |
| --- | --- |
| `dbcp1` | `Dbcp65` |
| `dbcp2` | `Dbcp270` |
| `dbcp3` | `org.apache.commons.dbcp.datasources.Dbcp369` |
| `dbcp4` | `org.apache.commons.dbcp.Dbcp271` |
| `derby1` | `Derby4129` |
| `derby2` | `Derby5560` |
| `derby3` | `Derby5561` |
| `derby4` | `org.junit.runner.JUnitCore org.apache.derby.impl.services.reflect.Derby764` |
| `derby5` | `org.apache.derby.impl.store.raw.data.Derby5447` |
| `groovy1` | `Groovy3495` |
| `groovy2` | `Groovy4736` |
| `groovy3` | `Groovy5198` |
| `groovy4` | `groovy.servlet.Groovy6456` |
| `groovy5` | `groovy.util.Groovy6068` |
| `groovy6` | `org.codehaus.groovy.ast.Groovy4292` |
| `jdk6_1` | `Test4243978` |
| `jdk6_2` | `Test4742723` |
| `jdk6_3` | `Test4779253` |
| `jdk6_4` | `Test4813150` |
| `jdk6_5` | `Test6436220` |
| `jdk6_6` | `Test6492872` |
| `jdk6_7` | `Test6582568` |
| `jdk6_8` | `Test6588239` |
| `jdk6_9` | `Test6648001` |
| `jdk6_10` | `Test6927486` |
| `jdk6_11` | `Test6934356` |
| `jdk6_12` | `Test6977738` |
| `jdk6_13` | `Test7100996` |
| `jdk6_14` | `Test7132889` |
| `jdk7_1` | `Test7045594` |
| `jdk7_2` | `Test7122142` |
| `jdk7_3` | `Test7132378` |
| `jdk7_4` | `Test8010939` |
| `jdk7_5` | `Test8012019` |
| `jdk7_6` | `Test8023541` |
| `log4j1` | `Test44032` |
| `log4j2` | `com.main.Test41214` |
| `log4j3` | `org.apache.log4j.helpers.Test54325` |
| `log4j4` | `org.apache.log4j.Test38137` |
| `log4j5` | `org.apache.log4j.Test50463` |
| `lucene1` | `junit.textui.TestRunner org.apache.lucene.index.Test2783` |
| `lucene2` | `junit.textui.TestRunner org.apache.lucene.Test1544` |
| `pool1` | `Test120` |
| `pool2` | `Test146` |
| `pool3` | `Test149` |
| `pool4` | `Test162` |
| `pool5` | `org.apache.commons.pool.Test46` |

## Tests with Extra JVM Setup

Most tests can use the generic commands directly. These need extra handling copied from `testplans.alt/testscript/*.sh`:

- `jdk6_9`: add `-ea:sun.net.www.protocol.http.AuthenticationInfo -Dhttp.auth.serializeRequests=true`
- `jdk6_3`: first run `asm.LoggerModifier`, then run `Test4779253` with `-Xbootclasspath/p:classes`
- `jdk7_3`: first run `asm.FutureTaskModifier`, then run `Test7132378` with `-Xbootclasspath/p:classes`
- `jdk7_6`: create `classes/edu/illinois/jacontebe/globalevent`, copy `source/edu/illinois/jacontebe/globalevent/GlobalDriver.class` there, run `asm.ActivationModifier`, then run `Test8023541` with `-Xbootclasspath/p:classes -Djava.security.policy=source/security.policy`

For those special cases, the easiest source of truth is the corresponding shell script in [`testplans.alt/testscript`](./testplans.alt/testscript).

## Notes

- `scripts/install.sh` clears `source/*` and `outputs/*` before staging the selected test.
- Some tests create temporary files or directories; several original shell scripts remove them afterward.
- The original benchmark README notes that a few tests depend on older JDK behavior. If a test fails before your agent logic is relevant, check `docs/bugList.html` and the matching script under `testplans.alt/testscript/`.
