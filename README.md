# Capture And Replay

This project is a Java capture/replay tool that records a rich execution trace
at capture time and replays only a distilled synchronization schedule.

The key idea is that replay is not trying to rebuild the exact captured heap.
Instead, it tries to reapply the captured inter-thread boundary order while
letting ordinary field, array, and atomic values evolve naturally in the replay
run.

## End-To-End Model

Capture produces two artifacts in the working directory:

- `trace.bin`: the full binary trace
- `trace-boundaries.tsv`: sidecar metadata for replay-relevant boundary sites

Replay then performs three steps before the target program runs:

1. Distill `trace.bin` into `schedule-boundaries.tsv`
2. Run a mandatory static applicability check against the replay target
3. Load the distilled schedule into the coordinator and gate threads only at
   replay boundaries

The replay bootstrap is implemented in
[ReplayAgent.java](/home/enxing/capture_and_replay/replay/src/main/java/replay/ReplayAgent.java).

## What Capture Records

Capture is intentionally richer than replay. The instrumentation records:

- monitor and thread lifecycle events
- wait/notify/park/unpark events
- field accesses, including volatile writes
- array accesses
- atomic reads, writes, RMWs, and CAS
- allocation identity
- exception throws

This happens through [CaptureMonitor.java](/home/enxing/capture_and_replay/capture/src/main/java/capture/CaptureMonitor.java)
and [TraceLogger.java](/home/enxing/capture_and_replay/common/src/main/java/common/TraceLogger.java).

Every event gets a packed `seq` value:

- upper 32 bits: global epoch
- lower 32 bits: per-thread local sequence within that epoch

The epoch advances only on release-side events in
[TraceSemantics.java](/home/enxing/capture_and_replay/common/src/main/java/common/TraceSemantics.java),
such as `MONITOR_EXIT`, `THREAD_START`, `THREAD_NOTIFY`, `THREAD_UNPARK`,
`CLASS_INIT_END`, atomic write-like operations, and volatile field writes.

That gives capture a causal ordering signal that is richer than plain wall-clock
order.

## Why A Boundary Sidecar Exists

The binary trace stores compact event records. For replay distillation, the tool
also needs a stable source-level description of each replay-relevant boundary:

- `eventType`
- `className`
- `methodName`

That mapping is written to `trace-boundaries.tsv` by
[ReplayBoundaryRegistry.java](/home/enxing/capture_and_replay/common/src/main/java/common/ReplayBoundaryRegistry.java).

The sidecar matters because replay matches boundaries by logical source
descriptor, not by exact runtime object identity. The distiller reads the raw
boundary site id from `trace.bin`, joins it against `trace-boundaries.tsv`, and
turns it into a source-level schedule entry that can still be meaningful across
versions.

## Schedule Distillation

Schedule distillation is implemented in
[ScheduleDistiller.java](/home/enxing/capture_and_replay/replay/src/main/java/replay/ScheduleDistiller.java).

It scans every record in `trace.bin`, keeps only events that
`TraceSemantics.isReplayBoundary(...)` considers replay-relevant, resolves each
boundary site through `trace-boundaries.tsv`, and writes a compact schedule:

- `seq`
- `epoch`
- `roleId`
- `eventType`
- `rawSiteId`
- `className`
- `methodName`

The important reduction is this:

- capture stores value-rich object-level events
- replay keeps only boundary events that are safe and useful for source-level
  schedule coordination

The distilled artifact is `schedule-boundaries.tsv`.

## Why Object-Related Release Events Are Discarded

This is the most important design choice in the current branch.

Some events are release-side events in the capture trace but are intentionally
discarded from the replay schedule:

- volatile `FIELD_WRITE`
- `ATOMIC_WRITE`
- `ATOMIC_RMW`
- `ATOMIC_CAS`

You can see the policy in
[TraceSemantics.java](/home/enxing/capture_and_replay/common/src/main/java/common/TraceSemantics.java):
these events still advance capture epochs, but `isReplayBoundary(...)` rejects
event types that use the occurrence-counter path.

They are discarded for two related reasons.

First, they are object-related. A volatile write or atomic update is tied to a
particular receiver object, array slot, or concrete mutation occurrence. Those
details are fragile across runs and especially across versions. Requiring replay
to hit the same object-tied mutation in the same place would make the schedule
artifact too specific and too brittle.

Second, replay in this branch does not inject captured values back into the
program. Field reads, array reads, and atomic operations return the natural
runtime value in [ReplayMonitor.java](/home/enxing/capture_and_replay/replay/src/main/java/replay/ReplayMonitor.java).
If replay were to block on object-related release events while still allowing
natural values to differ, it could over-constrain the run and create artificial
stalls. In producer/consumer style code, progress often depends on a naturally
changing read observing that some shared state has advanced. Scheduling the
write-like object events without also repairing state can force threads to wait
for transitions that the natural run may reach in a different way or at a
different time.

So the current branch keeps the capture-side causal information from those
events, but discards them during replay scheduling. In practice that means:

- capture still observes volatile and atomic releases
- replay uses them to shape epoch order in the original trace
- distillation drops them from the replay artifact
- replay coordinates only stable source-level boundaries such as monitor,
  thread, wait/notify, park/unpark, and class-init events

That is why this branch is schedule replay rather than state replay.

## Static Analysis

Before replay starts, the tool runs a mandatory static applicability check via
[ScheduleStaticAnalyzer.java](/home/enxing/capture_and_replay/replay/src/main/java/replay/ScheduleStaticAnalyzer.java).

The analyzer is conservative and source-oriented. It does not try to prove that
the whole captured schedule still exists. Instead, it looks for strong evidence
that replay would be obviously wrong.

It works like this:

1. Load `schedule-boundaries.tsv`
2. Group expected boundary types by `(className, methodName)`
3. Inspect the replay target bytecode with ASM
4. Start from each scheduled method and follow reachable helper methods in the
   application
5. Collect synchronization-relevant events seen in that reachable slice
6. Reject only if an expected boundary is replaced by its inverse meaning

Examples of inverse relationships:

- expected `MONITOR_ENTER`, found reachable `MONITOR_EXIT`
- expected `THREAD_START`, found `THREAD_JOIN`
- expected `THREAD_WAIT`, found `THREAD_NOTIFY` or `THREAD_UNPARK`
- expected `THREAD_INTERRUPT`, found `THREAD_INTERRUPT_CHECK`

Two details are important:

- missing evidence is not an automatic rejection
- unknown compatibility falls through to dynamic replay

So static analysis is a guardrail, not a proof system. Its job is to stop
obvious schedule inversions before replay starts.

## Replay Coordination

If static analysis passes, replay loads the distilled schedule into
[ReplayCoordinator.java](/home/enxing/capture_and_replay/replay/src/main/java/replay/ReplayCoordinator.java).

The coordinator keeps:

- the globally ordered list of distilled schedule entries
- a per-role queue of replay-boundary events from the original trace
- the currently released epoch
- role liveness and startup state
- a global divergence/inapplicability flag

[ReplayMonitor.java](/home/enxing/capture_and_replay/replay/src/main/java/replay/ReplayMonitor.java)
is the runtime entrypoint for instrumented operations. When a thread reaches a
replay boundary, replay:

1. resolves the current thread to a logical `roleId`
2. identifies the current boundary by `eventType`, `className`, and
   `methodName`
3. asks the coordinator whether this role is the next scheduled boundary
4. blocks if another role owns the next boundary
5. consumes the boundary and advances the schedule index if it matches

This means replay is cooperative and boundary-based:

- threads run freely between boundaries
- non-boundary reads and writes are never forced to match capture
- replay only constrains the order in which scheduled boundaries may pass

The coordinator also keeps the original trace's boundary queues around as a
consistency backstop. When a scheduled boundary is matched, the coordinator
consumes the corresponding head event from that role's captured boundary queue
and advances `releasedEpoch` when the matched event is a release event.

## Divergence And Inapplicability

Replay can stop applying synchronization guidance in two main ways.

`dynamically_inapplicable`:
the current role reaches a boundary but does not belong to the distilled
schedule at all.

`dynamically_diverged`:
the role reaches a scheduled point, but the observed boundary descriptor does
not match the next expected schedule entry.

When either happens, the coordinator records the first failure and wakes waiting
threads so the replay run does not deadlock behind stale guidance.

## What Replay Deliberately Does Not Do

This branch does not:

- inject captured field values
- inject captured array values
- inject captured atomic results
- require exact object identity equality at replay time
- require exact event-for-event reproduction of the full trace

Methods such as `checkFieldInt(...)`, `checkArrayInt(...)`, and
`replayAtomicInt(...)` all return the natural runtime value.

That is the reason schedule distillation can stay small and portable: the
artifact captures boundary order, not full program state.

## Build

Requires Maven and JDK 11+.

```bash
mvn package
```

Faster rebuild:

```bash
mvn package -pl common,instr,capture,replay,test-app -am
```

Artifacts:

- `capture/target/trace-capture-agent.jar`
- `replay/target/trace-replay-agent.jar`
- `test-app/target/test-app.jar`

## Run

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

If replay cannot infer the analysis target from the classpath, set it
explicitly:

```bash
java -Dtool.static.analysis.path=/path/to/app.jar \
     -javaagent:replay/target/trace-replay-agent.jar \
     -cp test-app/target/test-app.jar Main
```

## Offline Tools

Distill a schedule manually:

```bash
java -cp replay/target/trace-replay-agent.jar replay.ScheduleDistiller
```

Run the static analyzer manually:

```bash
java -cp replay/target/trace-replay-agent.jar replay.ScheduleStaticAnalyzer \
     schedule-boundaries.tsv <app.jar-or-classes-dir>
```
