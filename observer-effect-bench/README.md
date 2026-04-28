# Observer Effect Benchmark

This module is a thin launcher around JCStress.

For each scenario, it runs:

- `plain`
- `capture` with the attached `trace-capture-agent.jar`

and saves the raw artifacts for both runs under a local `results/` directory.

The goal is to keep the experiment simple and trustworthy: run JCStress, keep
everything it produces, and inspect or post-process the artifacts later.

## What Gets Saved

Each run creates a directory like:

```text
observer-effect-bench/results/run-YYYYMMDD-HHMMSS/
```

Inside it, each scenario gets its own folder:

```text
<scenario>/
  plain/
    jcstress.log
    jcstress-results-....bin.gz
    results/
  capture/
    jcstress.log
    jcstress-results-....bin.gz
    results/
    trace-....bin
```

These raw logs and JCStress artifacts are the ground truth.

## Prerequisites

- JDK on `PATH`
- Maven on `PATH`
- Capture agent jar at one of:
  - `capture/target/trace-capture-agent.jar`
  - `libs/trace-capture-agent.jar`
- A JCStress workload jar

For the official sample suite, point `JCSTRESS_JAR` at the upstream samples jar,
for example:

```bash
JCSTRESS_JAR=/tmp/jcstress-src/jcstress-samples/target/jcstress.jar
```

## Build

From `observer-effect-bench`:

```bash
mvn -q -DskipTests package
```

## Run

Run the full suite:

```bash
JCSTRESS_JAR=/tmp/jcstress-src/jcstress-samples/target/jcstress.jar ./run.sh
```

Run one scenario and save both `plain` and `capture` artifacts:

```bash
JCSTRESS_JAR=/tmp/jcstress-src/jcstress-samples/target/jcstress.jar \
./run.sh 'org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_01_SynchronizedBarriers'
```

Run one scenario in raw `plain` mode only:

```bash
JCSTRESS_JAR=/tmp/jcstress-src/jcstress-samples/target/jcstress.jar \
./run.sh 'org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_01_SynchronizedBarriers' plain
```

Run one scenario in raw `capture` mode only:

```bash
JCSTRESS_JAR=/tmp/jcstress-src/jcstress-samples/target/jcstress.jar \
./run.sh 'org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_01_SynchronizedBarriers' capture
```

## Results Directory

By default results are written under:

```bash
observer-effect-bench/results/run-<timestamp>
```

You can override that with:

```bash
OBSERVER_RESULTS_DIR=/path/to/results \
JCSTRESS_JAR=/tmp/jcstress-src/jcstress-samples/target/jcstress.jar \
./run.sh
```

## Modes

- default `./run.sh`
  runs the full selected JCStress workload and saves artifacts for both modes
- `./run.sh <ScenarioClass>`
  runs one scenario in both `plain` and `capture`
- `./run.sh <ScenarioClass> plain`
  runs one scenario only in `plain`
- `./run.sh <ScenarioClass> capture`
  runs one scenario only in `capture`

## Notes

- The runner no longer tries to compute outcome comparisons itself.
- Use the saved `jcstress.log`, `jcstress-results-*.bin.gz`, and `results/`
  directories for analysis.
- `plain` and `capture` raw modes are useful when you want direct access to the
  underlying JCStress output for a single scenario.
