# Object-Centric Capture/Replay Spec

## Goal

Design a Java capture/replay system that:

- achieves deterministic replay
- avoids broad benchmark-specific exclusion policies
- records only concurrency-relevant events from the start
- keeps dependency-owned bug state visible when it actually participates
- suppresses harness and dependency noise when it stays irrelevant

This design is motivated by regression problems in suites like JaConTeBe, where:

- the test harness itself creates noisy synchronization and object traffic
- bug-relevant locks and shared state often live in dependency libraries
- package-based exclusions improve one benchmark and regress another

The long-term direction is:

- record a selective generic object-centric trace online
- derive from it an even smaller focused replay trace
- keep instrumentation semantics separate from replay semantics

## Core Principle

Do not try to replay all instrumented events.

Instead:

1. instrument broadly enough to detect object sharing, publication, and synchronization
2. log only events from the generic object-centric model
3. derive the focused replay trace from that generic trace

This is not:

- record everything, then reduce

It is:

- select online into a generic object-centric trace
- reduce offline into a focused replay trace

## Three Levels

### 1. Raw Execution

The full JVM execution.

This is too large and noisy to capture directly.

### 2. Generic Object-Centric Trace

The first actual logged artifact.

It already contains only selected semantic events:

- allocations
- publication
- thread lifecycle
- monitor/lock/condition events
- volatile/atomic events
- ordinary field/array accesses only for relevant or transitioning objects
- relevant nondeterminism

### 3. Focused Replay Trace

Derived from the generic trace.

This contains only the events replay must enforce.

## Object Model

Each object is classified online and then interpreted by the reducer.

Top-level states:

- `LOCAL`
- `REPLAY_RELEVANT`

Replay-relevant objects carry tags:

- `escaped`
- `shared`
- `sync`
- `volatile`

These tags accumulate monotonically.

## Tag Meaning

### `escaped`

The object crossed a publication boundary and may become visible outside strictly local scope.

### `shared`

The object was actually touched by more than one thread.

### `sync`

The object participated in synchronization:

- intrinsic monitor
- explicit lock
- condition variable
- wait/notify

### `volatile`

The object or static container participated in volatile memory semantics.

## Provenance Model

Relevance is not the same as provenance.

Dependency-owned objects can still be relevant if benchmark/application execution pulls them into the concurrency path.

### Thread Provenance

- `APP_THREAD`
- `NON_APP_THREAD`

### Object Provenance

- `APP_DERIVED`
- `NON_APP`
- optional `UNKNOWN`

## Provenance Rules

### Threads

1. the main application/benchmark thread starts as `APP_THREAD`
2. a thread object created by an `APP_THREAD` becomes app-derived
3. when such a thread is started, the child runtime thread becomes `APP_THREAD`

### Objects

1. an object allocated by an `APP_THREAD` starts as `APP_DERIVED`
2. an object published into an app-derived or replay-relevant container becomes `APP_DERIVED`
3. an object synchronized on by an `APP_THREAD` becomes app-connected

This is what allows dependency-owned lock objects in Groovy/Lucene/Log4j/DBCP to become relevant without treating the whole dependency as globally relevant.

## Generic Object-Centric Event Families

The generic model should be expressed semantically even if the physical trace format reuses current `BinarySchema` records.

### Thread Events

- `THREAD_START`
- `THREAD_JOIN`
- `THREAD_SLEEP`
- `THREAD_WAKEUP`
- `THREAD_YIELD`
- `THREAD_PARK`
- `THREAD_UNPARK`

### Intrinsic Monitor Events

- `MONITOR_ENTER`
- `MONITOR_EXIT`
- `WAIT`
- `NOTIFY`
- `NOTIFY_ALL`

### Explicit Lock Events

- `LOCK_ACQUIRE`
- `LOCK_RELEASE`

These are semantically distinct from monitor events even if current implementation temporarily reuses similar runtime machinery.

### Condition Variable Events

- `CONDITION_CREATE`
- `COND_AWAIT`
- `COND_SIGNAL`
- `COND_SIGNAL_ALL`

### Memory Events

- `FIELD_READ`
- `FIELD_WRITE`
- `ARRAY_READ`
- `ARRAY_WRITE`
- `VOLATILE_READ`
- `VOLATILE_WRITE`

### Atomic Events

- `ATOMIC_READ`
- `ATOMIC_WRITE`
- `ATOMIC_RMW`
- `ATOMIC_CAS`

### Object Lifecycle Events

- `ALLOC`
- `PUBLISH`

### Class Lifecycle Events

- `CLASS_INIT_BEGIN`
- `CLASS_INIT_END`

### Control / Nondeterminism Events

- `EXCEPTION_THROW`
- `NONDET_INT`
- `NONDET_LONG`

## Payload Contract

Conceptually every event has:

- `seq`
- `roleId`
- `eventKind`
- `primaryObj`
- `siteId`
- `arg1`
- `arg2`
- `arg3`
- `arg4`

The physical trace may keep using current `BinarySchema` fields like:

- `objSite`
- `objCount`
- `data1..data6`

The following defines semantic meaning.

### `ALLOC`

- `primaryObj`: allocated object
- `siteId`: allocation site
- `arg1`: class ID or class hash
- `arg2`: allocation kind
- `arg3`: dimensions or array kind
- `arg4`: reserved

### `PUBLISH`

- `primaryObj`: published object
- `siteId`: publication site
- `arg1`: publication kind
  - `1 = STATIC_FIELD`
  - `2 = INSTANCE_FIELD`
  - `3 = ARRAY_ELEMENT`
- `arg2`: container object ID or static container ID
- `arg3`: field ID / array index / container-specific payload
- `arg4`: reserved

### `MONITOR_ENTER`

- `primaryObj`: monitor object
- `siteId`: enter site
- `arg1`: monitor kind
  - `1 = monitorenter`
  - `2 = synchronized method`

### `MONITOR_EXIT`

Same shape as `MONITOR_ENTER`.

### `WAIT`

- `primaryObj`: waited-on monitor object
- `siteId`: wait site
- `arg1`: wakeup site ID if modeled
- `arg2`: wait kind
- `arg3`: timeout millis
- `arg4`: timeout nanos

### `NOTIFY`

- `primaryObj`: monitor object
- `siteId`: notify site

### `NOTIFY_ALL`

- `primaryObj`: monitor object
- `siteId`: notifyAll site

### `LOCK_ACQUIRE`

- `primaryObj`: lock object
- `siteId`: acquire site
- `arg1`: acquire kind
  - `1 = lock`
  - `2 = lockInterruptibly`
  - `3 = tryLock success`
  - `4 = timed tryLock success`
- `arg2`: timeout if any
- `arg3`: time unit if any
- `arg4`: reserved

### `LOCK_RELEASE`

- `primaryObj`: lock object
- `siteId`: unlock site

### `CONDITION_CREATE`

- `primaryObj`: condition object
- `siteId`: creation site
- `arg1`: owning lock object ID

### `COND_AWAIT`

- `primaryObj`: condition object
- `siteId`: await site
- `arg1`: wakeup site ID
- `arg2`: await kind
- `arg3`: timeout/deadline payload
- `arg4`: owning lock object ID if useful

### `COND_SIGNAL`

- `primaryObj`: condition object
- `siteId`: signal site

### `COND_SIGNAL_ALL`

- `primaryObj`: condition object
- `siteId`: signalAll site

### `THREAD_START`

- `primaryObj`: thread object
- `siteId`: start site
- `arg1`: child role ID if assigned
- `arg2`: parent role ID if recorded

### `THREAD_JOIN`

- `primaryObj`: joined thread object
- `siteId`: join site
- `arg1`: join kind
- `arg2`: timeout millis
- `arg3`: timeout nanos

### `THREAD_PARK`

- `primaryObj`: blocker object or null sentinel
- `siteId`: park site
- `arg1`: park kind
- `arg2`: timeout/deadline if any

### `THREAD_UNPARK`

- `primaryObj`: target thread object
- `siteId`: unpark site

### `FIELD_READ`

- `primaryObj`: base object or static container
- `siteId`: read site
- `arg1`: field ID
- `arg2`: flags
- `arg3`: value object ID or scalar payload
- `arg4`: reserved

### `FIELD_WRITE`

- `primaryObj`: base object or static container
- `siteId`: write site
- `arg1`: field ID
- `arg2`: flags
- `arg3`: written object ID or scalar payload
- `arg4`: reserved

If `arg3` is an object reference and the write is a publication boundary, also emit `PUBLISH`.

### `ARRAY_READ`

- `primaryObj`: array object
- `siteId`: read site
- `arg1`: index if available
- `arg2`: element kind
- `arg3`: value object ID or scalar payload

### `ARRAY_WRITE`

- `primaryObj`: array object
- `siteId`: write site
- `arg1`: index if available
- `arg2`: element kind
- `arg3`: written object ID or scalar payload

If `arg3` is an object reference and the write is a publication boundary, also emit `PUBLISH`.

### `VOLATILE_READ`

- `primaryObj`: owning object or static container
- `siteId`
- `arg1`: field ID
- `arg2`: value payload if needed

### `VOLATILE_WRITE`

- `primaryObj`: owning object or static container
- `siteId`
- `arg1`: field ID
- `arg2`: written payload if needed

### `ATOMIC_READ`

- `primaryObj`: atomic object or atomic array
- `siteId`
- `arg1`: op kind
- `arg2`: index if array-backed
- `arg3`: returned value

### `ATOMIC_WRITE`

- `primaryObj`
- `siteId`
- `arg1`: op kind
- `arg2`: index if array-backed
- `arg3`: written value

### `ATOMIC_RMW`

- `primaryObj`
- `siteId`
- `arg1`: op kind
- `arg2`: index if array-backed
- `arg3`: old/return value
- `arg4`: new/post value

### `ATOMIC_CAS`

- `primaryObj`
- `siteId`
- `arg1`: index if array-backed
- `arg2`: expected value
- `arg3`: update value
- `arg4`: result

### `CLASS_INIT_BEGIN`

- `primaryObj`: static class container
- `siteId`
- `arg1`: class ID

### `CLASS_INIT_END`

Same shape.

### `EXCEPTION_THROW`

- `primaryObj`: exception object
- `siteId`
- `arg1`: exception class ID

### `NONDET_INT`

- `primaryObj`: none
- `siteId`
- `arg1`: nondet kind
- `arg2`: value

### `NONDET_LONG`

- `primaryObj`: none
- `siteId`
- `arg1`: nondet kind
- `arg2`: value

## Static Container Identity

Static fields and class init need a stable non-heap identity.

Model each static owner as a synthetic container:

- keyed by class internal name
- stable across capture/replay
- used as `primaryObj` for static field and class-init events

## Runtime Provenance + Relevance State

Each object has runtime metadata:

- stable identity
- provenance
- first-touch thread
- second-thread-touch flag
- replay-relevant flag
- tag bitset
- detailed-logging-enabled flag
- allocation-logged flag

Suggested conceptual record:

```java
final class ObjectMeta {
    long objSite;
    int objCount;
    int creatorRole;

    byte provenance; // APP_DERIVED, NON_APP, UNKNOWN
    long firstTouchRole; // 0 if unset

    int tags; // escaped/shared/sync/volatile
    boolean replayRelevant;
    boolean detailedLoggingEnabled;

    boolean allocLogged;
}
```

## Online Runtime State Machine

### Initial State

Every object starts as:

- provenance from allocating thread
- `replayRelevant = false`
- no tags
- `detailedLoggingEnabled = false`

### On Allocation

When `ALLOC(o)` occurs:

- assign stable identity
- if allocating thread is `APP_THREAD`, mark `o` as `APP_DERIVED`
- otherwise mark `o` as `NON_APP`

### On Ordinary Touch

Touch includes:

- instance field read/write
- array read/write
- monitor enter/exit
- wait/notify
- volatile read/write

For ordinary field/array access on object `o`:

1. if `firstTouchThread(o)` unset, set it
2. else if same thread, do nothing
3. else:
   - add `shared`
   - set `replayRelevant = true`
   - set `detailedLoggingEnabled = true`
   - current access becomes loggable

### On Sync Use

When `o` participates in:

- monitor enter/exit
- wait/notify
- explicit lock acquire/release
- condition await/signal

then:

- add `sync`
- set `replayRelevant = true`
- set `detailedLoggingEnabled = true`
- if current thread is `APP_THREAD`, mark `o` as `APP_DERIVED`

### On Volatile Use

When `o` participates in a volatile event:

- add `volatile`
- set `replayRelevant = true`
- set `detailedLoggingEnabled = true`

### On Publish

When object `v` is published through a publication boundary:

- add `escaped`
- if container is app-derived or replay-relevant, mark `v` as `APP_DERIVED`
- emit `PUBLISH(v, ...)`

Conservative V1 rule:

- any object with any tag becomes `REPLAY_RELEVANT`

## Publication Boundaries

V1 publication boundaries:

1. write object reference to a static field
2. write object reference to a field of a replay-relevant or app-derived object
3. write object reference to an array element of a replay-relevant or app-derived array

Do not publish on:

- primitive writes
- null writes
- writes into strictly local containers

## What Counts As a Touch

For V1, these count as touches:

- instance field read/write
- array read/write
- monitor enter/exit
- `wait`
- `notify`
- `notifyAll`
- volatile read/write

Pure allocation alone is not a touch.

## Online Logging Policy

### Always Log

- thread lifecycle events
- sync events
- volatile events
- atomic events
- `PUBLISH`
- relevant nondeterminism

### Conditionally Log

Ordinary field/array accesses are logged only if:

- target object is already replay-relevant, or
- this access causes the object to become replay-relevant

This is the key cost-saving rule.

## Focused Replay Trace

The focused replay trace is derived from the generic trace.

Keep:

- thread lifecycle events
- monitor events
- wait/notify events
- lock events
- condition events
- park/unpark events
- volatile events
- atomic events
- class init events
- `ALLOC` for replay-relevant objects
- `PUBLISH` for replay-relevant objects
- field/array accesses on replay-relevant objects
- relevant nondeterministic events

Drop:

- accesses on local objects
- harness-local object traffic
- dependency-local bootstrap traffic
- local object lifecycle noise

Pruning is intentionally out of scope for V1.

## Reduction Pipeline

### Pass 1: Build Object Facts

Scan the generic trace and compute per-object facts:

- allocation
- first-touch role
- second-thread touch
- sync participation
- volatile participation
- publication edges
- provenance
- replay-relevant tags

### Pass 2: Mark Kept Events

Keep an event if:

1. it is always replay-relevant
2. it is `ALLOC` for a replay-relevant object
3. it is `PUBLISH` for a replay-relevant object
4. it is a field/array access on a replay-relevant object
5. it is a nondeterministic event needed by a kept event

### Pass 3: Materialize Replay Trace

Emit the focused replay trace.

For V1, it is acceptable to keep current event IDs where possible and just reduce the event set.

## Implementation Split

### Instrumentation Layer

Responsible for:

- extracting semantic events
- calling runtime metadata APIs
- emitting generic object-centric events

It should not own policy beyond identifying which helper to call.

### Capture Runtime

Responsible for:

- maintaining stable object identity
- maintaining per-object runtime metadata
- logging generic events

### Reducer

Responsible for:

- reading generic trace
- classifying objects
- deriving replay relevance
- emitting focused replay trace

### Replay Runtime

Responsible for:

- consuming focused replay trace
- enforcing ordering
- resolving object identities
- replaying nondeterminism

## Instrumentation vs Logging Scope

The system should distinguish:

- instrumentation scope
- logging scope

Not all instrumented code contributes logged events.

### Hard No-Instrument Layer

Keep a small principled exclusion layer for safety:

- agent internals
- trace runtime internals
- ASM/tooling libraries
- forbidden bootstrap/runtime classes
- unstable generated proxies if required

### Semantic Logging Filter

For instrumented code:

- do not log everything
- log only semantic events from the generic object-centric model
- ordinary accesses are logged only for replay-relevant or transitioning objects

### Why This Handles JaConTeBe-Like Cases

Dependency-owned objects can still become relevant if:

- benchmark/app threads touch them
- they participate in sync
- they are published into replay-relevant state
- they are shared across threads

Harness-local objects stay silent if they never enter that path.

This is how the system can preserve Groovy/Lucene/Log4j/DBCP lock behavior while suppressing harness noise.

## Mapping To Existing Code

The current codebase already has a strong starting point.

Relevant current pieces:

- `instr/src/main/java/instr/SyncTransformer.java`
- `common/src/main/java/common/BinarySchema.java`
- `common/src/main/java/common/TraceLogger.java`
- `capture/src/main/java/capture/CaptureMonitor.java`
- `replay/src/main/java/replay/ReplayMonitor.java`
- `common/src/main/java/common/IdentityMapper.java`

Current hooks already cover:

- intrinsic monitors
- `Object.wait/notify/notifyAll`
- explicit locks
- conditions
- `LockSupport`
- thread lifecycle
- fields
- arrays
- volatile accesses
- atomics
- class init
- nondeterminism

The main additions are:

- first-class `ALLOC`
- first-class `PUBLISH`
- semantic separation between monitor and explicit lock events
- semantic separation between monitor wait/notify and conditions
- online runtime object-state tracking for selective logging

## `IdentityMapper` / Runtime Metadata API

The runtime identity/state layer should expose:

### Identity

```java
ObjectMeta getOrCreateMeta(Object o);
void registerAllocation(Object o, long allocSiteId);
BirthId getBirthId(Object o);
```

### Provenance

```java
void markAppDerived(Object o);
boolean isAppDerived(Object o);

void markThreadAppDerived(Thread t);
boolean isAppThread();
long currentRoleId();
```

### Ordinary Touch

```java
TouchResult recordOrdinaryTouch(Object target, long roleId);
```

Suggested return type:

```java
final class TouchResult {
    boolean becameReplayRelevant;
    boolean shouldLogDetailedNow;
}
```

### Sync / Volatile / Atomic Touch

```java
TransitionResult recordSyncTouch(Object target, long roleId);
TransitionResult recordVolatileTouch(Object target, long roleId);
TransitionResult recordAtomicTouch(Object target, long roleId);
```

Suggested return type:

```java
final class TransitionResult {
    boolean becameReplayRelevant;
    boolean becameAppDerived;
}
```

### Publish

```java
PublishResult recordPublish(Object value, Object container, long siteId, int kind);
PublishResult recordStaticPublish(Object value, String ownerClass, long siteId, int fieldId);
```

Suggested return type:

```java
final class PublishResult {
    boolean emitted;
    boolean becameReplayRelevant;
    boolean becameAppDerived;
}
```

### Query

```java
boolean shouldLogDetailed(Object o);
boolean isReplayRelevant(Object o);
int getTags(Object o);
byte getProvenance(Object o);
```

### Static Containers

```java
Object getStaticContainer(String ownerInternalName);
ObjectMeta getStaticContainerMeta(String ownerInternalName);
```

## Hot-Path Rules

### Ordinary Field / Array Access

For object `o`:

1. lookup metadata
2. if `detailedLoggingEnabled(o)`, emit full field/array event
3. else:
   - update first-touch ownership
   - if second-thread touch, promote to replay-relevant and emit current access
   - otherwise emit nothing

### Reference Write

For write of object reference `v` into `container`:

1. determine whether this is a publication boundary
2. if yes:
   - mark `v` escaped
   - possibly mark `v` app-derived
   - emit `PUBLISH(v, container, site)`
3. if `container` or `v` is replay-relevant as needed, emit ordinary write event too

### Sync / Volatile / Atomic

Always emit these events.

Also use them to:

- mark relevant tags
- propagate app-derived provenance
- enable detailed logging for participating objects

## V1 Simplifications

These simplifications are intentional for the first implementation:

- any tagged object becomes replay-relevant
- no pruning pass
- explicit locks may initially reuse current monitor-like runtime machinery
- focused replay trace may retain current event IDs where convenient
- thread-start handoff modeling can remain simple at first

## Non-Goals For V1

- perfect static identification of relevant objects
- zero exclusions
- minimal trace by aggressive pruning
- full semantic minimization of publication paths

## Main Design Summary

The long-term system should work like this:

1. `SyncTransformer` and runtime hooks observe semantic events
2. online runtime metadata marks objects as local or replay-relevant
3. only generic object-centric events are logged
4. the reducer derives a focused replay trace from that generic trace
5. replay consumes only the focused replay trace

This preserves dependency-owned bug state when it matters, suppresses harness-local noise, and avoids a growing benchmark-specific exclusion policy as the main correctness mechanism.
