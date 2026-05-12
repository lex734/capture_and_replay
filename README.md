# Capture and Replay

This repository contains a Java capture/replay system for concurrent programs.

At a high level it works in four stages:

1. `Stage A`: instrument the target program
2. `Stage B`: capture runtime events plus semantic object information
3. `Stage C`: reduce the raw capture into a replay-oriented trace
4. `Stage D`: replay against the reduced trace

The current implementation is intentionally close to the `divergence-finder` branch in its replay coordination model, while adding a semantic object layer so replay does not depend purely on allocation order.

## Build

Requires Maven and a recent JDK.

```bash
mvn package
```

Useful artifacts:

- `capture/target/trace-capture-agent.jar`
- `replay/target/trace-replay-agent.jar`
- `fidelity-benchmark/target/fidelity-benchmark.jar`
- `test-app/target/test-app.jar`

## Quick Start

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

The working directory will contain:

- `trace.bin`
  Raw captured events
- `trace-semantic.tsv`
  Persisted semantic object events from capture
- `trace-reduced.tsv`
  Reduced replay trace used by Stage D

## Pipeline

### Stage A: Instrumentation

Instrumentation lives mainly in:

- `instr/src/main/java/instr/SyncTransformer.java`
- `capture/src/main/java/capture/CaptureMonitor.java`
- `replay/src/main/java/replay/ReplayMonitor.java`

What is instrumented:

- explicit monitor enter/exit bytecodes
- `Thread.start`, `join`, timeout join variants
- `ReentrantLock` / `Condition` wrapper calls
- field, array, atomic, class-init, exception, and nondeterministic events

Important policy:

- explicit `MONITORENTER` / `MONITOREXIT` are instrumented
- synchronized methods are not given special synthetic monitor events
- wrapper-based `ReentrantLock` / `Condition` calls are instrumented explicitly

### Stage B: Capture

Core classes:

- `common/src/main/java/common/TraceLogger.java`
- `common/src/main/java/common/BinarySchema.java`
- `common/src/main/java/common/v1/SemanticTraceRegistry.java`

Capture produces two coupled streams:

1. raw events in `trace.bin`
2. semantic object events in memory, later persisted to `trace-semantic.tsv`

#### Raw event sequencing

Each captured event has a packed sequence:

`captureSeq = (epoch << 32) | localSeq`

Where:

- `epoch` advances only on JMM release-side events
- `localSeq` is thread-local and preserves per-thread execution shape inside an epoch

This means capture sequence is not a global total order by itself. It is a compact causal key:

- high 32 bits: release-oriented epoch
- low 32 bits: thread-local order

To preserve a real cross-thread append order, raw records are also written to `trace.bin` in actual file-slot order.

#### Semantic capture

Semantic capture records:

- object/field/array/atomic identities
- thread lifecycle identities
- sync object identities
- source sites and target roles where relevant

This is the layer that lets replay identify objects semantically instead of relying only on raw allocation order.

### Stage C: Reduction

Core class:

- `common/src/main/java/common/v1/TraceReducer.java`

The reducer consumes:

- `trace.bin`
- `trace-semantic.tsv`

And produces:

- `trace-reduced.tsv`

The reducer does not simply keep every raw event. It keeps a replay-oriented subset plus constraints.

#### Current reduction policy

The important current rules are:

- only replay roles `> 0` are considered
- ignored role `-1` and global role `0` are not replay-relevant
- the replay-relevant window starts at the first `THREAD_START`
- cross-role owners are preserved owner-centrically:
  if an owner participates across roles, keep all runtime events for that owner in the concurrent window
- lock/condition protocol events are always retained:
  `MONITOR_ENTER`, `MONITOR_EXIT`, `THREAD_WAIT`, `THREAD_NOTIFY`, `THREAD_NOTIFY_ALL`
- static object-valued field noise is pruned aggressively

#### Replay sequence in the reduced trace

Reduced replay events use a new key:

`replaySeq = (reducedOrder << 32) | originalLocalSeq`

Where:

- high 32 bits: synthetic reduced causal order
- low 32 bits: original captured local sequence

This is deliberate:

- replay needs a unique, monotonic event id for the reduced trace
- we still preserve thread-local shape from capture in the low half

Epoch is not inferred from `replaySeq`. It is stored separately in `trace-reduced.tsv` and loaded through `ReducedTraceRegistry`.

#### Constraints emitted by Stage C

The reducer records:

- `THREAD_ORDER`
- `DOMAIN_ORDER`
- `JMM_SYNCHRONIZES_WITH`
- `THREAD_START_CAUSAL`

These are consumed by replay as metadata, not as a second trace format.

### Stage D: Replay

Core classes:

- `replay/src/main/java/replay/ReplayCoordinator.java`
- `replay/src/main/java/replay/ReplayMonitor.java`
- `common/src/main/java/common/v1/SemanticIdentity.java`
- `common/src/main/java/common/v1/ReducedTraceRegistry.java`

Replay loads the reduced trace and uses:

- per-role pending queues
- separate replay epochs
- semantic object binding
- value injection only when the replay model explicitly allows it

#### Replay object model

Replay does not assume a trace object and a runtime object are matched by pure allocation order.

Instead it uses:

- semantic object events from capture
- runtime semantic observations during replay
- `SemanticIdentity` bindings

Static prebinding handles obvious stable roots early. Everything else can bind on first legal runtime use.

#### Tolerance policy

The current tolerance model is object-level:

- unbound or unrelated runtime objects may pass through
- once a runtime object is bound to a replay-relevant trace object, replay becomes strict on that object

In other words, tolerance is for extra runtime objects, not fuzziness on already-bound relevant objects.

## Same As Divergence-Finder

The current design intentionally preserves several `divergence-finder` ideas.

### 1. Epochs are for synchronization, not identity

Replay epochs are used to coordinate JMM-style release/acquire progress.

They are not used to decide whether two objects are “the same object”.

### 2. Per-role queue discipline

Replay is still fundamentally queue-based per role:

- each role has a head event
- replay tries to consume that role’s next legal event
- thread-start causality is respected explicitly

### 3. Natural monitor scheduling

Replay does not try to invent synthetic scheduling for synchronized methods.

Where possible, it lets the JVM’s natural monitor/lock behavior happen and coordinates around the recorded events.

### 4. Release-driven epoch advancement

Only release-like events open later replay epochs, in the same spirit as `divergence-finder`:

- monitor exit
- thread start
- notify / notifyAll
- unpark
- interrupt
- class-init end
- selected atomic and volatile release events

## Different From Divergence-Finder

The biggest differences are deliberate.

### 1. Semantic object matching

`divergence-finder` is closer to exact replay over directly captured identities.

This branch adds semantic binding so replay can identify objects without relying purely on allocation order.

That is the main architectural difference.

### 2. Reduced trace instead of near-direct raw replay

`divergence-finder` is closer to replaying the captured stream directly.

This branch inserts Stage C:

- compute replay relevance
- preserve the concurrent object/protocol slice
- emit a reduced replay trace and constraints

So Stage D is replaying a reduced artifact, not the original full raw stream.

### 3. Replay sequence format

`divergence-finder` uses the captured event order directly much more directly.

This branch uses:

- capture sequence for raw capture semantics
- reduced replay sequence for Stage D event identity
- separate stored epoch metadata

That split is intentional:

- replay order and replay epoch are not the same thing
- thread-local local sequence is still preserved

### 4. Persisted semantic artifact

This branch persists semantic capture explicitly in:

- `trace-semantic.tsv`

That allows reduction to be rerun from persisted semantic state rather than depending only on live in-memory shutdown state.

## Files and Responsibilities

- `common/TraceLogger.java`
  Capture-side event creation and causal epoch assignment
- `common/BinarySchema.java`
  Raw binary event storage
- `common/v1/SemanticTraceRegistry.java`
  Captured and replay semantic object event registry
- `common/v1/TraceReducer.java`
  Reduction policy and reduced trace emission
- `common/v1/ReducedTraceRegistry.java`
  Reduced trace loading and lookup
- `common/v1/SemanticIdentity.java`
  Replay object binding policy
- `replay/ReplayCoordinator.java`
  Role queues, epoch gates, matching, and fidelity reporting
- `replay/ReplayMonitor.java`
  Replay-side runtime hooks
- `capture/CaptureMonitor.java`
  Capture-side runtime hooks
- `instr/SyncTransformer.java`
  Bytecode transformation
- `fidelity-benchmark/FidelityBenchmark.java`
  Capture/replay benchmark harness

## Known Current Boundaries

The design is intentionally stronger than “just outcome reproduction”, but weaker than exact replay of every runtime instruction.

In practice, the hardest cases are:

- wrapper-based lock/condition handoffs
- deadlock/timeout captures where shutdown timing matters
- traces where semantic binding and reduced replay order interact tightly

So when debugging fidelity, the first question is usually:

- is this a capture ordering bug?
- a reduction policy bug?
- or a replay coordination bug?

That separation is the main reason the repository now persists:

- raw events
- semantic events
- reduced replay trace

as three distinct artifacts.
