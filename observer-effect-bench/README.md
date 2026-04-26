# Observer Effect Benchmark Suite

This benchmark measures whether the capture agent changes concurrency behavior
while observing a program.

For each selected JCStress test, the suite runs:

| Run | Configuration |
|---|---|
| Plain | No agent attached |
| Capture | `trace-capture-agent.jar` attached |

It then compares outcome distributions and flags observer effect when a
`FORBIDDEN` or `INTERESTING` outcome appears in plain mode but is suppressed in
capture mode.

## Test Suite Selection

Primary workload is the official JCStress samples jar. The runner selects:

- `org.openjdk.jcstress.samples.jmm.*`
- `org.openjdk.jcstress.samples.primitives.*`
- `org.openjdk.jcstress.samples.problems.*`

The runner excludes:

- `org.openjdk.jcstress.samples.api.*` (API tutorial tests)

If the target jar does not contain JCStress sample tests, the runner falls back
to local `observer.Scenario*` tests in this module.

## What The Different Tests Are About

### JMM Family

`org.openjdk.jcstress.samples.jmm.basic.*`

- Data races and visibility with plain vs volatile/opaque/synchronized access
- Atomicity and word tearing behavior
- Coherence and causality constraints
- Progress/liveness differences across memory access modes

`org.openjdk.jcstress.samples.jmm.advanced.*`

- Multi-copy atomicity and IRIW-style outcomes
- Release/acquire ordering pitfalls
- Misplaced or partial synchronization patterns
- Volatile vs final publication effects
- Cases where synchronization is present but insufficient as a fence

### Concurrency Family

`org.openjdk.jcstress.samples.primitives.lazy.*`

- Lazy initialization correctness under races
- One-shot publication variants and broken wrappers

`org.openjdk.jcstress.samples.primitives.singletons.*`

- Singleton construction/publication patterns
- Broken DCL variants vs correct implementations

`org.openjdk.jcstress.samples.primitives.rmw.*`

- CAS/RMW semantics under contention
- Success/failure ordering effects and witness behavior

`org.openjdk.jcstress.samples.primitives.mutex.*`

- Mutual exclusion algorithm correctness
- Locking primitives and critical section safety

`org.openjdk.jcstress.samples.primitives.library.*`

- Library usage patterns under concurrent access
- Correct vs incorrect composition of thread-safe components

`org.openjdk.jcstress.samples.problems.classic.*`

- Classical concurrency problems (for example dining philosophers,
  producer-consumer)

`org.openjdk.jcstress.samples.problems.racecondition.*`

- Read-modify-write races
- Check-then-act race patterns

## Why This Is A Good Workload

- Broad coverage: avoids cherry-picking a few litmus tests
- Standardized expectations: JCStress already encodes acceptable vs forbidden
  outcomes
- Reproducible methodology: same tests, same harness, plain vs capture delta

## Prerequisites

- JDK 11+ on `PATH`
- Maven 3.x on `PATH`
- Capture agent jar in `<repo-root>/capture/target/trace-capture-agent.jar` or
  `<repo-root>/libs/trace-capture-agent.jar`

Build project agents from repo root if needed:

```bash
mvn -DskipTests package
```

## Build This Module

```bash
cd observer-effect-bench
./build.sh
```

## Build Official JCStress Samples

```bash
git clone --depth 1 https://github.com/openjdk/jcstress.git /tmp/jcstress-src
cd /tmp/jcstress-src
mvn clean verify -pl jcstress-samples -am -DskipTests
```

This produces:

```bash
/tmp/jcstress-src/jcstress-samples/target/jcstress.jar
```

## Run

Run with official samples (recommended):

```bash
cd <repo-root>/observer-effect-bench
JCSTRESS_JAR=/tmp/jcstress-src/jcstress-samples/target/jcstress.jar ./run.sh
```

Run with local fallback suite:

```bash
./run.sh
```

## Useful Environment Variables

- `JCSTRESS_JAR`: target jcstress workload jar (defaults to local
  `observer-effect-bench/target/jcstress.jar`)
- `OBSERVER_TEST_TIME_SECS`: per-test time budget (default `5`)
- `OBSERVER_TIMEOUT_SECS`: subprocess timeout in seconds (default `120`)
- `OBSERVER_MAX_TESTS`: cap number of tests for smoke runs

## Local Fallback Tests

- `observer.ScenarioStoreBuf`
- `observer.ScenarioDekker`
- `observer.ScenarioMessagePass`
- `observer.ScenarioLoadBuffer`
- `observer.ScenarioLostUpdate`

These are small litmus/control tests used only when samples are unavailable.
