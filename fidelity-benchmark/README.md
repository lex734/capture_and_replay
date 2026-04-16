# Fidelity Benchmark

Measures how faithfully the replay agent reproduces a captured run by running
the same workload under capture once and under replay N times, then tallying
how often the program's final output naturally matches the capture.

## What It Measures

| Metric | Meaning |
|---|---|
| **Final output match** | Fraction of replay runs where every tracked write location's natural final value equals the captured value |
| **Natural agreement rate** | Fraction of individual write events where the replay thread produced the correct value without injection |
| **Injection rate** | Fraction of write events where the replay agent had to override the natural value with the captured one |
| **Structural divergences** | Runs where the event-type order differed from the trace (injection stops after this point) |

A high **final output match** with a low **natural agreement rate** means injection
is doing a lot of work — the replay is structurally guided but threads often
produce different values on their own. A high **natural agreement rate** means
the concurrent behaviour is essentially deterministic under the same schedule.

## Prerequisites

Build all modules from the repo root:

```bash
mvn -DskipTests package
```

This produces:
- `capture/target/trace-capture-agent.jar`
- `replay/target/trace-replay-agent.jar`
- `fidelity-benchmark/target/fidelity-benchmark.jar`

## Running

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     <capture-agent.jar> <replay-agent.jar> [runs] [workload-class]
```

**Arguments:**

| Argument | Required | Default |
|---|---|---|
| `capture-agent.jar` | yes | — |
| `replay-agent.jar` | yes | — |
| `runs` | no | `100` |
| `workload-class` | no | `fidelity.workload.WorkloadAtomicCounter` |

**Example — 100 runs with the default workload:**

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     capture/target/trace-capture-agent.jar \
     replay/target/trace-replay-agent.jar
```

**Example — 50 runs with the plain counter (data race workload):**

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     capture/target/trace-capture-agent.jar \
     replay/target/trace-replay-agent.jar \
     50 fidelity.workload.WorkloadPlainCounter
```

## Available Workloads

| Class | Description | Race type |
|---|---|---|
| `fidelity.workload.WorkloadAtomicCounter` | 4 threads, AtomicInteger ±1, 100 000 iters | `ATOMIC_RMW` — near-deterministic baseline |
| `fidelity.workload.WorkloadPlainCounter` | 4 threads, plain `int` ±1, 100 000 iters | `FIELD_WRITE` race — lower natural agreement |
| `fidelity.workload.WorkloadVolatileWrite` | 4 threads write their index to a volatile int | `FIELD_WRITE` (volatile release) |
| `fidelity.workload.WorkloadSharedObject` | 4 threads publish `Holder` objects via volatile ref | `FIELD_WRITE` (volatile), object graph |
| `fidelity.workload.WorkloadArrayElement` | 4 threads write to every element of int[100] | `ARRAY_WRITE` — per-element tracking |

## Sample Output

```
=== Fidelity Benchmark: fidelity.workload.WorkloadAtomicCounter  (100 runs) ===

Capturing...
Capture output  : Final: 0

Replaying...
  10 / 100 complete
  20 / 100 complete
  ...
  100 / 100 complete

===== FIDELITY BENCHMARK RESULTS =====
Workload                : fidelity.workload.WorkloadAtomicCounter
Runs                    : 100
Final output match      : 100 / 100  (100.0%)
Structural divergences  : 100 / 100  (100.0%)
Avg write events / run  : 4
Natural agreement rate  : 62.9%
Injection rate          : 37.1%
=======================================
```

**Note on "Structural divergences":** The existing divergence detector flags any RMW value
mismatch as a structural divergence and stops injection. This means the `Avg write events / run`
reflects only the events processed before the first RMW disagreement (often very few). The
**Final output match** and **Natural agreement rate** are the primary fidelity signals.

## Running a Single Instrumented Replay Manually

If you want to inspect the per-run properties file directly:

```bash
java -Dtool.fidelity.output=result.properties \
     -javaagent:replay/target/trace-replay-agent.jar \
     -cp fidelity-benchmark/target/fidelity-benchmark.jar \
     fidelity.workload.WorkloadAtomicCounter

cat result.properties
```

The properties file format:

```
match=true
structural_divergence=false
write_events=400000
natural_agreements=289600
injections=110400
locations_matched=1
locations_total=1
locations_unseen=0
```
