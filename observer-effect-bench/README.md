# Observer Effect Benchmark Suite

Demonstrates the **observer effect** of the capture agent: attaching the agent
to a program makes hardware-level concurrency bugs unobservable.

Each scenario is a JCStress litmus test with one or more `FORBIDDEN` outcomes —
outcomes that are impossible under sequential consistency (SC) but that can
appear on real hardware (x86 store buffers, JIT compiler reordering).  The
suite runs each test twice and compares which `FORBIDDEN` outcomes appear:

| Run | Configuration |
|-----|--------------|
| **Plain** | No agent — program runs freely; hardware bugs are observable |
| **Capture** | `trace-capture-agent.jar` attached — agent serialises shared-memory accesses; hardware bugs disappear |

---

## Prerequisites

- JDK 11 or later on `$PATH`
- Maven 3.x on `$PATH` (for the initial build)
- Agent JARs available in one of:
  - `<repo-root>/libs/` — pre-built
  - `<repo-root>/capture/target/` — after `mvn package` from the repo root

> **Important:** The agent must include the `exclude=` argument support added
> in `instr/Agent.java`.  If the pre-built JAR in `libs/` pre-dates that
> change, rebuild with `mvn package -DskipTests` from the repo root.

---

## Build

```bash
cd observer-effect-bench
./build.sh
```

`run.sh` calls `build.sh` automatically if `target/jcstress.jar` is missing.

---

## Running the Full Suite

```bash
./run.sh
```

Runs all three scenarios in plain and capture modes and prints a comparison:

```
======================================================================
Scenario: observer.ScenarioStoreBuf
======================================================================
  Running plain (no agent)...
  Running with capture agent...

  Outcome         Plain (no agent)      Capture (agent)   Observer Effect
  ------------------------------------------------------------------------------------------
  0, 0            47 [FORBIDDEN]        0 [FORBIDDEN]     *** HIDDEN — agent suppresses hardware-only outcome
  0, 1            498231 [ACCEPTABLE]   500000 [ACCEPTABLE]
  1, 0            497891 [ACCEPTABLE]   500000 [ACCEPTABLE]
  1, 1              3831 [ACCEPTABLE]        0 [ACCEPTABLE]

  Observer effect DETECTED for observer.ScenarioStoreBuf
```

---

## Running a Single Scenario

```bash
# No agent (baseline — hardware bugs visible)
./run.sh observer.ScenarioStoreBuf plain

# With capture agent (hardware bugs suppressed)
./run.sh observer.ScenarioStoreBuf capture
```

---

## Scenarios

| Class | Litmus test | SC-forbidden outcome | Hardware mechanism |
|---|---|---|---|
| `ScenarioStoreBuf` | Store buffering | `(r1=0, r2=0)` | x86 store buffer: both loads read stale cache lines before either store propagates |
| `ScenarioDekker` | Dekker mutex | `(r1=1, r2=1)` (both enter CS) | x86 store buffer: same mechanism as SB applied to flag variables |
| `ScenarioMessagePass` | Message passing | `(r1=1, r2=0)` (flag seen, data not) | JIT compiler reorders the sender's two plain stores |

---

## Why the Outcomes Disappear Under the Agent

The capture agent instruments every plain field access by wrapping it in
`monitorenter` / `monitorexit` calls (via ASM bytecode rewriting).  This:

1. **Flushes the store buffer** — `monitorenter` implies a full memory fence on
   x86, so the local store buffer is drained before any load executes.  The
   `(0, 0)` and `(1, 1)` outcomes require both stores to remain buffered while
   the loads proceed — the fence makes this impossible.

2. **Prevents JIT store reordering** — the JIT compiler treats the barriers
   introduced by the monitor operations as ordering constraints, preventing it
   from reordering the sender's `data = 42` and `flag = 1` stores.  The `(1, 0)`
   outcome in `ScenarioMessagePass` requires this reordering.

The agent's instrumentation of `org.openjdk.jcstress.*` is suppressed via
`-javaagent:capture.jar=exclude=org/openjdk/jcstress` so that only the test
actor classes are instrumented and JCStress's own harness runs unmodified.

---

## File Layout

```
observer-effect-bench/
├── pom.xml                            # Standalone Maven project
├── src/main/java/observer/
│   ├── ScenarioStoreBuf.java          # Store-buffering litmus test
│   ├── ScenarioDekker.java            # Dekker mutual-exclusion litmus test
│   ├── ScenarioMessagePass.java       # Message-passing litmus test
│   └── ObserverEffectRunner.java      # Forks JCStress, compares outcome distributions
├── build.sh                           # mvn package → target/jcstress.jar
├── run.sh                             # Entry point
└── README.md
```
