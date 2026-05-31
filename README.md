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
