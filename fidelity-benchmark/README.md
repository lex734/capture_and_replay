# Fidelity Benchmark

Measures how faithfully the replay agent reproduces captured runs across the
SCTBench suite. For each class: runs 10 capture trials by default, replays each
successful capture 10 times, and reports per-class and aggregate metrics
grouped by whether the capture outcome was buggy or clean.

## What It Measures

For each replay run:

| Metric | Meaning |
| --- | --- |
| **Fidelity score** | For one replay run: `events_matched / events_total`, where `events_matched` is the number of trace events the replay agent consumed and matched before the first divergence (or all events if replay completes without diverging). Averaged over all N replay runs per class and across classes. A score of 1.0 means every event in the trace was faithfully reproduced; a score of 0.5 means replay diverged halfway through. |
| **Outcome reproduction rate** | Fraction of replay runs whose outcome (bug signal present or absent) matched the capture outcome. Measures whether divergence, when it occurs, changes the observable result. |
| **Divergence rate** | Fraction of replay runs where the replay agent reported a structural divergence (event-type or object-identity mismatch against the trace). |
| **Natural agreement rate** | Among valued replay events, fraction where the thread's natural value already matched the captured one without injection. |
| **Injection rate** | Among valued replay events, fraction where replay had to inject the captured value. |

### Interpreting the fidelity score

- **High score, zero divergences**: replay is reproducing nearly all captured events.
- **High score, some divergences**: most runs replay cleanly; occasional divergences happen late in the trace.
- **Low score with divergences**: replay is structurally mismatched early; the captured schedule is not being reliably enforced.
- **Low score with zero divergences**: replay processes only a prefix of the trace and then threads exit early, leaving the remaining events in their role queues unconsumed. No mismatch is reported because a divergence is only raised when `awaitTurn` is called and the event does not match. This failure mode is invisible to divergence detection alone; the fidelity score is the only signal that replay is not reproducing the full execution.

## Prerequisites

Build the agents and benchmark jar from the repo root:

```bash
mvn -DskipTests package
```

This produces:
- `capture/target/trace-capture-agent.jar`
- `replay/target/trace-replay-agent.jar`
- `fidelity-benchmark/target/fidelity-benchmark.jar`

You also need the SCTBench jar:

```bash
cd benchmark/bms/SCTBench && ./gradlew shadowJar
```

This produces `benchmark/bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar`.

## Running

Run from the repo root:

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     <capture-agent.jar> <replay-agent.jar> <sctbench.jar> [class-or-classlist.txt]
```

Arguments:

| Argument | Required | Default |
| --- | --- | --- |
| `capture-agent.jar` | yes | - |
| `replay-agent.jar` | yes | - |
| `sctbench.jar` | yes | - |
| `class-or-classlist.txt` | no | bundled `sctbench.txt` (all 28 classes) |

The benchmark currently uses:
- `10` capture trials per class
- `10` replay runs per successful capture

Example, full suite:

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     capture/target/trace-capture-agent.jar \
     replay/target/trace-replay-agent.jar \
     benchmark/bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar
```

Example, single class:

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     capture/target/trace-capture-agent.jar \
     replay/target/trace-replay-agent.jar \
     benchmark/bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar \
     cmu.pasta.fray.benchmark.sctbench.cs.origin.AccountBad
```

Example, custom class list:

```bash
java -cp fidelity-benchmark/target/fidelity-benchmark.jar fidelity.FidelityBenchmark \
     capture/target/trace-capture-agent.jar \
     replay/target/trace-replay-agent.jar \
     benchmark/bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar \
     my-classes.txt
```

## SCTBench Classes

The bundled `sctbench.txt` lists 28 classes from the SCTBench suite. Deadlock
benchmarks treat process timeout as the bug signal.

## Sample Output

```
=== Fidelity Benchmark: SCTBench (10 capture trials x 10 replays per capture) ===

--- cmu.pasta.fray.benchmark.sctbench.cs.origin.AccountBad ---
  Capture  1/10       : bug (exit 1)
  ...
  Capture 10/10       : clean (exit 0)
  Successful captures: 10 / 10
  bug   captures     : 7 / 10
    Trace size         : 102 events
    Replay runs        : 70
    Complete runs      : 63 / 70
    Structural divs    : 2 / 70
    Outcome reproduced : 65 / 70  (92.86%)
    ...complete runs   : 60 / 63
    Matched (complete) : 102.00 avg / run  (100.00%)
    Natural agreement  : 430 / 700  (61.43%)
    Injections         : 270 / 700  (38.57%)
  clean captures     : 3 / 10
    Trace size         : 98 events
    Replay runs        : 30
    Complete runs      : 29 / 30
    Structural divs    : 1 / 30
    Outcome reproduced : 28 / 30  (93.33%)
    ...complete runs   : 27 / 29
    Matched (complete) : 98.00 avg / run  (100.00%)
    Natural agreement  : 200 / 300  (66.67%)
    Injections         : 100 / 300  (33.33%)

===== SUMMARY =====
Classes tested        : 28
Classes replayed      : 22 / 28
Bug captures          : 110
Bug replay runs       : 1100
Bug complete runs     : 1009 / 1100 runs  (91.7%)
Bug structural divs   : 46 / 1100 runs  (4.2%)
Bug outcome repr.     : 972 / 1100 runs  (88.4%)
Bug ...complete runs  : 930 / 1009 runs  (92.2%)
Bug matched complete  : 96.1%
Bug natural agr.      : 4200 / 6600  (63.6%)
Bug injections        : 2400 / 6600  (36.4%)
Clean captures        : 87
Clean replay runs     : 870
Clean complete runs   : 801 / 870 runs  (92.1%)
Clean structural divs : 21 / 870 runs  (2.4%)
Clean outcome repr.   : 845 / 870 runs  (97.1%)
Clean ...complete runs: 780 / 801 runs  (97.4%)
Clean matched complete: 98.4%
Clean natural agr.    : 1900 / 2900  (65.5%)
Clean injections      : 1000 / 2900  (34.5%)
===================
```
