# Overhead Benchmark Suite

Measures the wall-clock overhead introduced by the capture and replay agents against a plain (no-agent) baseline.

Each workload is a standalone Java program modelled after `test-app/src/main/java/correctness/`, scaled up to produce measurable execution times.

**No Maven required** — the suite compiles with plain `javac` and locates agent JARs automatically from `libs/` (or `capture/target` / `replay/target` after a Maven build).

---

## Prerequisites

- JDK 11 or later on `$PATH`
- Agent JARs available in one of:
  - `<repo-root>/libs/` — pre-built, always present
  - `<repo-root>/capture/target/` and `<repo-root>/replay/target/` — after `mvn package`

---

## Build

```bash
cd overhead-bench
./build.sh
```

`run.sh` calls `build.sh` automatically if the jar is missing.

---

## Running the Full Benchmark

```bash
./run.sh
```

Runs every workload in three modes (baseline → capture → replay), discards warm-up rounds, and prints a summary table:

```
Workload                     Baseline (ms)     Capture (ms)      Replay (ms)   Cap/Base  Rep/Base
---------------------------------------------------------------------------------------------------------
WorkloadPlainCounter          245.3 ±  12.1      412.7 ±  18.3      389.2 ±  15.6   1.68x     1.59x
WorkloadAtomicCounter         ...
```

---

## Running a Single Workload

Run one workload in a specific mode for a direct comparison:

```bash
# No agent (baseline)
./run.sh overhead.WorkloadPlainCounter plain

# With capture agent
./run.sh overhead.WorkloadPlainCounter capture

# Capture then replay (capture runs first to produce trace.bin in the current directory)
./run.sh overhead.WorkloadPlainCounter replay
```

Available workloads:

| Class | What it stresses |
|---|---|
| `overhead.WorkloadPlainCounter` | 4 threads × 100 000 plain int read-modify-write cycles |
| `overhead.WorkloadAtomicCounter` | 4 threads × 100 000 `AtomicInteger` CAS operations |
| `overhead.WorkloadVolatileWrite` | 4 threads × 100 000 volatile field writes |
| `overhead.WorkloadSharedObject` | 4 threads × 50 000 object allocations + volatile publish |
| `overhead.WorkloadArrayElement` | 4 threads × 1 000 rounds of 100-element array writes |

---

## Overhead Metrics

### Output columns

| Column | Meaning |
|---|---|
| **Baseline (ms)** | Wall-clock time with no agent attached. Includes JVM startup. |
| **Capture (ms)** | Wall-clock time with `trace-capture-agent.jar` attached. The agent instruments bytecode at load time and records every synchronization event to `trace.bin`. |
| **Replay (ms)** | Wall-clock time with `trace-replay-agent.jar` attached. The agent reads the previously captured `trace.bin` and enforces the recorded event ordering. |
| **Cap/Base** | Capture overhead multiplier — `Capture / Baseline`. A value of `2.0x` means capture doubled the runtime. |
| **Rep/Base** | Replay overhead multiplier — `Replay / Baseline`. |

Times are reported as **mean ± std-dev** over `MEASURE` timed rounds (default: 10), preceded by `WARMUP` discarded rounds (default: 3).

### What the numbers mean

- **Cap/Base near 1.0** — the capture agent adds little overhead; instrumentation and event recording are cheap relative to the workload.
- **Cap/Base >> 1.0** — the capture agent's instrumentation or I/O is a significant fraction of total runtime. High values are expected for fine-grained synchronization workloads (e.g. `WorkloadAtomicCounter`) where nearly every instruction is instrumented.
- **Rep/Base vs Cap/Base** — if replay overhead is lower than capture overhead, event recording I/O is the dominant cost of capture. If replay overhead is higher, enforced scheduling (blocking/waiting to match the recorded order) is the bottleneck.
- **Std-dev** — high variance indicates that external scheduling noise (OS, GC) is inflating some runs. If std-dev > 20% of the mean, increase `ITERS` / `ROUNDS` in the workload files or reduce background load on the machine.

### JVM startup note

Each workload is run in a **fresh JVM subprocess** per round, so JVM startup time (~50–150 ms) is included in all three columns equally. Its effect on the ratios is minimal for workloads that take ≥ 500 ms; for very fast workloads it inflates all three numbers but compresses the ratios toward 1.0. Increase iteration counts if baseline times are below ~200 ms.

---

## Configuration

Tunable constants in `BenchmarkRunner.java`:

| Constant | Default | Effect |
|---|---|---|
| `WARMUP` | 3 | Rounds discarded before timing |
| `MEASURE` | 10 | Timed rounds used for mean ± std |
| `TIMEOUT` | 120 s | Per-process hard timeout |

Workload iteration counts are set in each `Workload*.java` file (`ITERS`, `ROUNDS`).

---

## File Layout

```
overhead-bench/
├── src/overhead/
│   ├── WorkloadPlainCounter.java   # plain int race, 4 threads
│   ├── WorkloadAtomicCounter.java  # AtomicInteger, 4 threads
│   ├── WorkloadVolatileWrite.java  # volatile int, 4 threads
│   ├── WorkloadSharedObject.java   # volatile object publish, 4 threads
│   ├── WorkloadArrayElement.java   # array element race, 4 threads
│   └── BenchmarkRunner.java        # forks subprocesses, collects timing
├── build.sh                        # javac + jar, no Maven
├── run.sh                          # entry point
└── README.md
```
