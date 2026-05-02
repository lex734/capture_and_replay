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
Workload                     Baseline (ms)     Capture (ms)      Replay (ms)    PlainBug      CapBug  Cap/Base  Rep/Base
-------------------------------------------------------------------------------------------------------------------------------
WorkloadPlainCounter          245.3 +-  12.1     412.7 +-  18.3     389.2 +-  15.6       4/39       7/41     1.68x     1.59x
WorkloadAtomicCounter         ...
```

---

## Running SCTBench as the Suite

```bash
./run.sh --sctbench
```

This runs the overhead benchmark loop against classes listed in
`../benchmark/fray_benchmark/assets/sctbench.txt`, using
`../benchmark/bms/SCTBench/build/libs/fray-benchmark-1.0-SNAPSHOT.jar`
as the classpath under test.

If the SCTBench jar is missing, build it first:

```bash
cd ../benchmark/bms/SCTBench
./gradlew jar
```

---

## Running a Single Workload

Run one workload in a specific mode for a direct comparison:

```bash
# No agent (baseline)
./run.sh overhead.WorkloadPlainCounter plain

# With capture agent
./run.sh overhead.WorkloadPlainCounter capture

# Capture then replay (single capture run produces trace.bin in the current directory)
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
| **Baseline (ms)** | Mean ± std-dev wall-clock time of plain runs that actually observed the bug during the 10 s measurement window. Includes JVM startup. `N/A` if no buggy plain runs were seen. |
| **Capture (ms)** | Mean ± std-dev wall-clock time of capture runs that actually observed the bug during the 10 s measurement window. The agent instruments bytecode at load time and records events to `trace.bin`. |
| **Replay (ms)** | Mean ± std-dev wall-clock time over `MEASURE` replay rounds using the single saved bug-observing capture trace. A replay run is accepted if it reproduces the desired bug outcome, even if replay also reports an incomplete execution. |
| **PlainBug** | `buggy/attempts` observed in the 10 s plain measurement window. |
| **CapBug** | `buggy/attempts` observed in the 10 s capture measurement window. |
| **Cap/Base** | Capture overhead multiplier — `Capture / Baseline`. A value of `2.0x` means capture doubled the runtime. |
| **Rep/Base** | Replay overhead multiplier — `Replay / Baseline`. |

`Baseline (ms)` and `Capture (ms)` are computed from all buggy outcomes observed during a fixed **10 s harness wall-clock window** per mode. `Replay (ms)` is still reported as **mean ± std-dev** over `MEASURE` replay rounds (default: 10), preceded by `WARMUP` discarded rounds (default: 3). Replay acceptance is **outcome-first**: if a replay run reproduces the target bug, it is counted even when replay marks the run incomplete.

### What the numbers mean

- **Cap/Base near 1.0** — the capture agent adds little overhead on the subset of runs that actually hit the buggy outcome.
- **Cap/Base >> 1.0** — the capture agent's instrumentation or I/O is a significant fraction of total runtime. High values are expected for fine-grained synchronization workloads (e.g. `WorkloadAtomicCounter`) where nearly every instruction is instrumented.
- **Rep/Base vs Cap/Base** — if replay overhead is lower than capture overhead, event recording I/O is the dominant cost of capture. If replay overhead is higher, enforced scheduling (blocking/waiting to match the recorded order) is the bottleneck.
- **PlainBug / CapBug** — these counts show how many buggy executions were available to average within the 10 s window, and how many total attempts were needed to find them.
- **Std-dev** — high variance indicates that external scheduling noise (OS, GC) is inflating some runs. If std-dev > 20% of the mean, increase `ITERS` / `ROUNDS` in the workload files or reduce background load on the machine.
- **`N/A` ratios** — if no buggy plain runs were observed in 10 s but capture did find buggy runs, the row is still printed with partial data and ratios left as `N/A`.

### JVM startup note

Each workload is run in a **fresh JVM subprocess** per round, so JVM startup time (~50–150 ms) is included in all three columns equally. Its effect on the ratios is minimal for workloads that take ≥ 500 ms; for very fast workloads it inflates all three numbers but compresses the ratios toward 1.0. Increase iteration counts if baseline times are below ~200 ms.

---

## Configuration

Tunable constants in `BenchmarkRunner.java`:

| Constant | Default | Effect |
|---|---|---|
| `WARMUP` | 3 | Rounds discarded before timing |
| `MEASURE` | 10 | Timed replay rounds used for mean ± std |
| `MEASURE_WINDOW_SECONDS` | 10 | Harness wall-clock window used to collect buggy plain/capture runs |
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
