# Capture And Replay

This branch is a Java capture/replay tool that records a rich execution trace
and replays a distilled boundary schedule.

The important design point for this branch is:

- replay is schedule-based, not value-injecting
- replay coordinates only selected replay boundaries
- replay does not try to force ordinary field, array, or atomic values back to
  what capture observed

## What This Branch Does

Capture records a rich binary trace in `trace.bin`. That trace includes:

- thread/monitor behavior
- field accesses
- array accesses
- atomic operations
- allocation identity
- exception throws

Capture also writes `trace-boundaries.tsv`, which is a sidecar that maps raw
boundary sites to `(eventType, className, methodName)`.

Replay does not directly replay the entire trace. Instead it:

1. Loads `trace.bin`
2. Distills a smaller `schedule-boundaries.tsv`
3. Runs a mandatory static applicability check against the target app
4. If applicable, coordinates only the distilled replay boundaries

The replay entrypoint is [ReplayAgent.java](/home/enxing/capture_and_replay/replay/src/main/java/replay/ReplayAgent.java).

## Replay Boundary Policy

The single boundary predicate lives in
[TraceSemantics.java](/home/enxing/capture_and_replay/common/src/main/java/common/TraceSemantics.java).

In this branch, `isReplayBoundary(...)` means:

- include thread behavior
- include non-object-related epoch releases
- exclude object-tied releases such as volatile field writes and atomic writes

So schedule replay currently coordinates things like:

- `MONITOR_ENTER`
- `MONITOR_EXIT`
- `THREAD_START`
- `THREAD_JOIN`
- `THREAD_WAIT`
- `THREAD_NOTIFY`
- `THREAD_NOTIFY_ALL`
- `THREAD_PARK`
- `THREAD_UNPARK`
- `THREAD_SLEEP`
- `THREAD_YIELD`
- `THREAD_INTERRUPT`
- `THREAD_INTERRUPT_CHECK`
- `CLASS_INIT_BEGIN`
- `CLASS_INIT_END`

And it intentionally does not schedule object-tied release events like:

- volatile `FIELD_WRITE`
- `ATOMIC_WRITE`
- `ATOMIC_RMW`
- `ATOMIC_CAS`

That invariant is what keeps tests like
[LinkedListProducerConsumerTest.java](/home/enxing/capture_and_replay/test-app/src/main/java/correctness/LinkedListProducerConsumerTest.java)
from hanging in schedule replay when progress depends on natural read outcomes.

## Is Value Injection Used Here?

No, not in the active replay path on this branch.

The current replay monitor executes operations naturally and only blocks on
replay boundaries. For example:

- field replay methods return `naturalValue`
- array replay methods return `naturalValue`
- atomic replay methods return `naturalValue`

You can see that directly in
[ReplayMonitor.java](/home/enxing/capture_and_replay/replay/src/main/java/replay/ReplayMonitor.java).

Examples:

- `checkFieldInt(...)` returns `naturalValue`
- `checkArrayInt(...)` returns `naturalValue`
- `replayAtomicInt(...)` returns `naturalValue`

So the current branch uses replay as schedule coordination, not as state repair.

## Difference From `divergence-finder`

`divergence-finder` is a different replay model.

### This branch

- replays a distilled boundary schedule
- runs a mandatory static applicability check first
- coordinates only replay boundaries
- preserves natural program values
- does not inject captured field/array/atomic values back into the run
- is closer to “boundary-guided re-execution”

### `divergence-finder`

- replays against the raw trace much more directly
- includes value-aware replay paths in `ReplayCoordinator`
- can return captured `traceValue` instead of the natural runtime value
- tracks fidelity/injection statistics
- is closer to “find where natural execution diverges from the captured run”

On `divergence-finder`, methods such as:

- `awaitTurnFieldInt(...)`
- `awaitTurnFieldLong(...)`
- `awaitTurnFieldObj(...)`
- `awaitTurnArrayInt(...)`
- `awaitTurnArrayLong(...)`
- `awaitTurnArrayObj(...)`

can return the captured trace value when it differs from the natural value.
That is value injection.

This branch does not do that.

## Build

Requires Maven and JDK 11+.

```bash
mvn package
```

Useful faster rebuild:

```bash
mvn package -pl common,instr,capture,replay,test-app -am
```

Artifacts:

- `capture/target/trace-capture-agent.jar`
- `replay/target/trace-replay-agent.jar`
- `test-app/target/test-app.jar`

## Running

Capture:

```bash
java -javaagent:capture/target/trace-capture-agent.jar \
     -cp test-app/target/test-app.jar Main
```

Replay:

```bash
java -javaagent:replay/target/trace-replay-agent.jar \
     -cp test-app/target/test-app.jar Main
```

If replay cannot infer the target jar or classes root from the classpath, set:

```bash
java -Dtool.static.analysis.path=/path/to/app.jar \
     -javaagent:replay/target/trace-replay-agent.jar \
     -cp test-app/target/test-app.jar Main
```

## Generated Files

Written in the working directory:

- `trace.bin`: raw captured trace
- `trace-boundaries.tsv`: raw capture-time boundary metadata
- `schedule-boundaries.tsv`: distilled replay schedule

## Offline Tools

Distill the schedule manually:

```bash
java -cp replay/target/trace-replay-agent.jar replay.ScheduleDistiller
```

Run the static analyzer manually:

```bash
java -cp replay/target/trace-replay-agent.jar replay.ScheduleStaticAnalyzer \
     schedule-boundaries.tsv <app.jar-or-classes-dir>
```
