# Capture and Replay

This repository contains a Java agent-based capture/replay system for concurrent programs. It instruments a target JVM during capture, records both raw runtime events and semantic object metadata, reduces that capture into a replay-oriented trace, and then replays against that reduced trace with coordination and semantic object binding.

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

- `trace.bin`: raw captured events
- `trace-semantic.tsv`: semantic object events from capture
- `trace-reduced.tsv`: reduced replay trace consumed by replay

## How It Works

The tool runs in four stages.

### 1. Instrumentation

Both capture and replay install the same bytecode transformer, [SyncTransformer.java](common/src/main/java/common/SyncTransformer.java), and switch behavior by setting `tool.mode`.

The transformer:

- assigns stable site IDs from site strings so capture and replay agree across class-load order
- instruments monitor operations, thread lifecycle events, arrays, fields, atomics, exceptions, and nondeterministic events
- records allocation sites through `IdentityMapper.registerAllocation(...)`
- routes sync checks to either `capture/CaptureMonitor` or `replay/ReplayMonitor`
- caches volatile-field metadata so cross-class field accesses can still be classified correctly

The main entrypoints are:

- [CaptureAgent.java](capture/src/main/java/capture/CaptureAgent.java)
- [ReplayAgent.java](replay/src/main/java/replay/ReplayAgent.java)

### 2. Capture

During capture, instrumented code calls into capture-side logging helpers, which append raw events to `trace.bin` and also record semantic object events in memory.

Two representations are produced:

- raw binary events in `trace.bin`
- semantic object events in `SemanticTraceRegistry`, later persisted to `trace-semantic.tsv`

Raw capture sequence numbers are packed as:

`captureSeq = (epoch << 32) | localSeq`

Where:

- `epoch` advances on release-like synchronization events
- `localSeq` preserves thread-local order within that epoch

This means the packed sequence is a compact causal key, not the only source of cross-thread ordering. The physical append order in `trace.bin` is also preserved.

Semantic capture records enough structure for replay to match objects by meaning rather than only by allocation order:

- field owners and field keys
- array owners and indices
- atomic owners
- thread lifecycle objects and target roles
- monitor/sync owners
- exception and nondeterministic sites

### 3. Reduction

At JVM shutdown, capture flushes `trace.bin`, saves `trace-semantic.tsv`, and then runs the reducer to build `trace-reduced.tsv`.

The reducer:

- reads raw events from `trace.bin`
- aligns them with semantic events from memory or `trace-semantic.tsv`
- filters to replay-supported, replay-relevant events
- keeps the concurrent window starting at the first `THREAD_START`
- retains synchronization protocol events and epoch-release events
- keeps cross-role shared-owner activity so replay can preserve causality and value flow
- emits replay constraints such as `THREAD_ORDER` and `THREAD_START_CAUSAL`

Reduced replay sequence numbers are packed as:

`replaySeq = (reducedOrder << 32) | originalLocalSeq`

Where:

- `reducedOrder` is a synthetic monotonic order in the reduced trace
- `originalLocalSeq` preserves thread-local shape from capture

Replay epoch data is stored separately in `trace-reduced.tsv` and loaded through `ReducedTraceRegistry`.

### 4. Replay

Replay loads `trace-reduced.tsv`, initializes replay state, installs the same transformer in replay mode, and uses `ReplayCoordinator` to block or release runtime events until they are legal according to the reduced trace.

Replay uses:

- per-role pending event queues
- shared-domain coordination for cross-thread field/object interactions
- replay epochs to gate progress on release/acquire structure
- semantic object binding through `SemanticIdentity`
- best-effort prebinding for stable static roots
- optional value injection when the recorded value is required and safe to realize

The replay model is intentionally stricter once an object is bound. Extra irrelevant runtime objects may exist, but once a runtime object is committed to a replay-relevant trace object, replay expects future uses to stay consistent with that binding.

#### Replay Constraints

The reducer emits `ReplayConstraint` records attached to events in `trace-reduced.tsv`. There are two kinds:

- **`THREAD_ORDER`**: Links consecutive events from the same role in the reduced trace. Each event in a role's pending queue cannot be consumed until the preceding event for that role has already been matched. This preserves per-thread program order across the reduced event set.

- **`THREAD_START_CAUSAL`**: Links a `THREAD_START` event to the first retained event of the child role. The child's first event cannot be consumed until the parent's `THREAD_START` has been matched. This preserves the causal edge between a parent launching a thread and the child's first recorded action.

At match time, `predecessorsSatisfied` checks that every constraint on a candidate event has its predecessor sequence in `matchedSeqs`. If any predecessor is still pending, `selectCandidate` returns `WAIT` and the calling thread blocks on `controlLock` until another thread makes progress.

Epoch gating adds a second gate on top of constraints. Certain release events (volatile field writes, `MONITOR_EXIT`, `THREAD_START`, atomic writes/RMWs/CAS, `THREAD_NOTIFY`, `THREAD_UNPARK`, `CLASS_INIT_END`) advance the global `releasedEpoch` counter when consumed. A role whose pending head event carries an epoch higher than `releasedEpoch` cannot proceed until all active roles that are behind that epoch have advanced past it, ensuring that cross-thread happens-before edges are respected.

#### Event Matching and Classification

When a replay hook fires, the coordinator calls `selectCandidate` against the role's pending queue head. The result is one of three states:

- **`FOUND`**: The runtime event matches the trace head — same packed type, same semantic context (field key, array index, etc.), and the runtime object is binding-compatible with the trace object identity. If predecessor constraints and the epoch gate are satisfied, the event is consumed and the role proceeds.
- **`WAIT`**: The runtime event looks compatible with the trace head (same shape and context, or the runtime object is already bound to a relevant trace identity) but cannot yet be committed — either because predecessor constraints are unsatisfied or because the epoch gate has not opened. The calling thread blocks on `controlLock` and retries.
- **`NO_MATCH`**: The runtime event does not match the current trace head for this role. The coordinator returns `null` and execution continues without coordination for this call.

For valued events, replay can inject the recorded value back into the runtime. Fields and array elements inject their trace value on reads when the runtime diverges; nondeterministic sources (e.g. `System.nanoTime`) always inject the captured value; CAS results inject the captured success/failure value.

The fidelity report printed at JVM shutdown tracks the following classification flags:

| Flag | Meaning |
|---|---|
| `match` | All write locations seen during replay matched their trace-recorded values |
| `binding_conflict` | A runtime object was committed to a trace object already bound to a different runtime object (structural divergence) |
| `degraded` | Replay injected at least one value (nondeterministic source, field read/write, object field, CAS result) |
| `value_diverged` | At least one valued event's runtime value differed from its trace value |
| `unsupported` | Replay timed out waiting for constraints without detecting a deadlock, or an atomic RMW result differed from the trace |
| `incomplete` | The trace had unconsumed events when replay finished (`unconsumed_tail`) or ended with unmatched unsupported events (`unsupported_tail`) |
| `unapplicable` | A role's thread exited while it still had pending trace events; those events were phantom-consumed to unblock downstream roles |
| `unreachable` | A deadlock was detected and confirmed during replay |

#### Deadlock Detection

Every call to `awaitSemanticEvent` runs against a 2-second timeout (`MATCH_TIMEOUT_MS`). On expiry, `checkForDeadlock` builds a combined wait-for graph from two sources:

1. **Coordinator edges**: For each role whose pending head event has unsatisfied `THREAD_ORDER` or `THREAD_START_CAUSAL` predecessor constraints, an edge is added from that role to the role that owns the blocking predecessor. Epoch-gate stalls add edges as well: a role waiting for epoch `E` to be released depends on every other role whose pending head epoch is below `E`.

2. **JVM lock-wait edges**: Using `ThreadMXBean`, threads in `BLOCKED`, `WAITING`, or `TIMED_WAITING` state are inspected for the lock they are waiting on. If that lock is held by another replay role's thread (and the wait is not inside the coordinator's own `controlLock`), an edge is added from the waiting role to the holding role.

`hasCycleInWaitForGraph` then runs a DFS over the combined graph. If a cycle is found, the timeout is treated as a genuine deadlock: `hasDiverged` is set to `true`, the `unreachable` flag is recorded, and all waiting threads are notified so they can exit. Without a cycle, the timeout is treated as an unsupported scenario: the `unsupported` flag is recorded and the coordinator returns `null` for that event, allowing replay to continue in a degraded state.

## Core Files

These are the main files to read first.

### Shared infrastructure in `common/`

- [SyncTransformer.java](common/src/main/java/common/SyncTransformer.java)
  The bytecode transformer. Instruments classes, assigns site IDs, resolves volatile fields, and switches between capture and replay monitors.

- [IdentityMapper.java](common/src/main/java/common/IdentityMapper.java)
  Maps runtime threads, objects, static fields, and allocations to stable IDs used by capture and replay.

- [BinarySchema.java](common/src/main/java/common/BinarySchema.java)
  Defines the raw event format, event kinds, and binary trace storage helpers for `trace.bin`.

- [TraceLogger.java](common/src/main/java/common/TraceLogger.java)
  Shared low-level event logging logic used by capture-side instrumentation paths.

- [SemanticTraceRegistry.java](common/src/main/java/common/SemanticTraceRegistry.java)
  Stores semantic object events during capture, saves them to `trace-semantic.tsv`, and reloads or exposes replay-side semantic metadata.

- [SemanticObjectEvent.java](common/src/main/java/common/SemanticObjectEvent.java)
  The semantic event model for fields, arrays, atomics, sync objects, thread events, exceptions, class init, and nondeterministic events.

- [SemanticIdentity.java](common/src/main/java/common/SemanticIdentity.java)
  The runtime binding layer that maps trace object identities to replay-time runtime objects and checks binding consistency.

- [ReducedTraceRegistry.java](common/src/main/java/common/ReducedTraceRegistry.java)
  In-memory representation of the reduced replay trace. Loads and saves `trace-reduced.tsv`, indexes epochs, domains, constraints, and reduced events.

- [FieldKey.java](common/src/main/java/common/FieldKey.java)
  Canonical identifier for a field: owner internal name, field name, and descriptor.

- [FieldInteractionDomain.java](common/src/main/java/common/FieldInteractionDomain.java)
  Identifies a shared field interaction domain for replay coordination.

- [ReplayConstraint.java](common/src/main/java/common/ReplayConstraint.java)
  Encodes reducer-emitted ordering constraints used during replay.

- [AgentRuntimeConfig.java](common/src/main/java/common/AgentRuntimeConfig.java)
  Parses agent args and decides which classes should be instrumented.

### Capture-side files in `capture/`

- [CaptureAgent.java](capture/src/main/java/capture/CaptureAgent.java)
  Capture entrypoint. Resets shared state, initializes `trace.bin`, installs the transformer, and flushes/reduces output on shutdown.

- [CaptureMonitor.java](capture/src/main/java/capture/CaptureMonitor.java)
  Capture-side monitor hooks called from instrumented bytecode. Bridges runtime operations into capture logging.

- [TraceLogger.java](capture/src/main/java/capture/TraceLogger.java)
  Capture-specific event logging wrapper that records concrete runtime events and semantic metadata.

- [TraceReducer.java](capture/src/main/java/capture/TraceReducer.java)
  The reduction stage. Converts raw capture plus semantic metadata into the reduced replay trace and emitted constraints.

### Replay-side files in `replay/`

- [ReplayAgent.java](replay/src/main/java/replay/ReplayAgent.java)
  Replay entrypoint. Loads `trace-reduced.tsv`, initializes replay state, prebinds obvious static roots, installs hooks, and enables replay mode.

- [ReplayMonitor.java](replay/src/main/java/replay/ReplayMonitor.java)
  Replay-side monitor hooks injected into the target program. Each hook asks `ReplayCoordinator` whether the runtime action is currently legal.

- [ReplayCoordinator.java](replay/src/main/java/replay/ReplayCoordinator.java)
  The core replay engine. Owns pending queues, epoch gating, semantic matching, divergence handling, value injection, fidelity reporting, and thread lifecycle coordination.

## File Outputs

- `trace.bin`
  Raw binary event stream written during capture.

- `trace-semantic.tsv`
  Human-readable semantic event log used to rebuild object-level meaning during reduction and replay.

- `trace-reduced.tsv`
  Reduced replay-oriented trace with domains, epochs, semantic events, reduced raw events, and replay constraints.

## Design Notes

- Replay coordination is queue-based per role rather than based on a single global schedule.
- Epochs are used for synchronization progress, not as a substitute for object identity.
- Semantic object binding is what allows replay to survive differences in allocation order.
- Reduction is intentionally lossy: it keeps the events needed for replay, not a verbatim copy of all capture activity.
