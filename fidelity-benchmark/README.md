# Fidelity Benchmark

Measures how faithfully the replay agent reproduces captured runs across the
SCTBench suite. For each class: captures one run unconditionally, then replays
that trace N times (default 30) and reports per-class and aggregate metrics.

## What It Measures

| Metric | Meaning |
|---|---|
| **Fidelity score** | For one replay run: `events_matched / events_total`, where `events_matched` is the number of trace events the replay agent consumed and matched before the first divergence (or all events if replay completes without diverging). Averaged over all N replay runs per class and across classes. A score of 1.0 means every event in the trace was faithfully reproduced; a score of 0.5 means replay diverged halfway through. |
| **Outcome reproduction rate** | Fraction of replay runs whose outcome (bug signal present or absent) matched the capture outcome. Measures whether divergence, when it occurs, changes the observable result. |
| **Divergence rate** | Fraction of replay runs where the replay agent reported a structural divergence (event-type or object-identity mismatch against the trace). |
| **Natural agreement rate** | Among valued events (field reads/writes, atomics) processed during replay: fraction where the thread's natural value already matched the captured value without injection. |
| **Injection rate** | Fraction of valued events where the replay agent had to override the thread's natural value with the captured one. |

### Interpreting the fidelity score

- **1.0 (100%)** — replay matched every event in the trace; the captured schedule was perfectly reproduced.
- **High score, some divergences** — most runs replay cleanly; occasional divergences happen late in the trace.
- **Low score with divergences** — replay is structurally mismatched early; the captured schedule is not being reliably enforced.
- **Low score with zero divergences** — replay processes only a prefix of the trace and then threads exit early, leaving the remaining events in their role queues unconsumed. No mismatch is reported because a divergence is only raised when `awaitTurn` is *called* and the event does not match — an `awaitTurn` call that is never made is invisible. This happens when a thread exits (or takes a branch that bypasses an instrumented operation) before consuming all of its queued events. The bug may not manifest because it depends on operations in the unconsumed tail of the trace. **This failure mode is invisible to divergence detection alone; the fidelity score is the only signal that replay is not reproducing the full execution.**

A high **natural agreement rate** means the program's concurrent behaviour is largely deterministic under the replayed schedule, so the replay agent rarely needs to inject values.

## Prerequisites

Build all modules from the repo root:

```bash
mvn -DskipTests package
```

This produces:
- `capture/target/trace-capture-agent.jar`
- `replay/target/trace-replay-agent.jar`
- `fidelity-benchmark/target/fidelity-benchmark.jar`

You also need the SCTBench jar (built from `benchmark/bms/SCTBench`):

```bash
cd benchmark/bms/SCTBench && ./gradlew shadowJar
```

This produces `benchmark/bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar`.

## Running

Run from the repo root (the working directory is used for `trace.bin`):

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     <capture-agent.jar> <replay-agent.jar> <sctbench.jar> [runs] [class-or-classlist.txt]
```

**Arguments:**

| Argument | Required | Default |
|---|---|---|
| `capture-agent.jar` | yes | — |
| `replay-agent.jar` | yes | — |
| `sctbench.jar` | yes | — |
| `runs` | no | `30` |
| `class-or-classlist.txt` | no | bundled `sctbench.txt` (all 28 classes) |

**Example — full suite, 30 replays per class:**

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     capture/target/trace-capture-agent.jar \
     replay/target/trace-replay-agent.jar \
     benchmark/bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar
```

**Example — single class, 50 replays:**

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     capture/target/trace-capture-agent.jar \
     replay/target/trace-replay-agent.jar \
     benchmark/bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar \
     50 cmu.pasta.fray.benchmark.sctbench.cs.origin.AccountBad
```

**Example — custom class list:**

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     capture/target/trace-capture-agent.jar \
     replay/target/trace-replay-agent.jar \
     benchmark/bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar \
     30 my-classes.txt
```

## SCTBench Classes

The bundled `sctbench.txt` lists 28 classes from the SCTBench suite covering
assertion-based bugs (data races, atomicity violations), deadlocks, and
ordering bugs. Deadlock benchmarks (Carter01Bad, Deadlock01Bad, Phase01Bad,
Sync01Bad, Sync02Bad) treat process timeout as the bug signal.

## Sample Output

```
=== Fidelity Benchmark: SCTBench (30 replay runs per class) ===

--- cmu.pasta.fray.benchmark.sctbench.cs.origin.AccountBad ---
  Capture: bug (exit 1)
  Fidelity score     : 94.3%
  Outcome reproduced : 28 / 30  (93.3%)
  Diverged           : 2 / 30
  Natural agreement  : 61.2%  Injection: 38.8%  (1200 valued events)

--- cmu.pasta.fray.benchmark.sctbench.cs.origin.Deadlock01Bad ---
  Capture: bug (timed out)
  Fidelity score     : 100.0%
  Outcome reproduced : 30 / 30  (100.0%)
  Diverged           : 0 / 30

--- cmu.pasta.fray.benchmark.sctbench.cs.origin.FsbenchBad ---
  Capture: no trace.bin produced (exit 1)

...

===== SUMMARY =====
Classes tested        : 28
Classes replayed      : 22 / 28
Mean fidelity score   : 91.7%
Outcome reproduced    : 88.4%
Divergence rate       : 4.2%
Natural agreement     : 63.8%
Injection rate        : 36.2%
===================
```
