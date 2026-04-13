# Evaluation Plan

## Tools Under Comparison

| Tool | Category | Level | Status |
|---|---|---|---|
| This tool | Record-and-replay | JVM agent (`-javaagent`) | Live — run directly |
| RR | Record-and-replay | OS/ptrace | Live — open source |
| LEAP | Record-and-replay | JVM | Prior results only — source unavailable |
| JPF (Java PathFinder) | Model checker | JVM | Live — open source, coverage contrast only |


---

## Benchmarks

| Suite | Purpose | Notes |
|---|---|---|
| **DaCapo 9.12-bach** | Overhead measurement | Real-world Java apps; concurrent workloads (`h2`, `avrora`, `lusearch`, `sunflow`, `tomcat`) stress the agent's hot paths. Use fixed iteration count per workload for reproducible wall-clock comparison. |
| **JaConTeBe** | Replay determinism, coverage | Java concurrency bugs from real-world projects. LEAP's published results cover the real-world bug subset — use the same bugs for a direct reproduction rate comparison. |
| **SCTBench (Java subset)** | Coverage, applicability | Confirms which programs each tool can handle. Verify that the Java port of each benchmark is used; the original suite is C/C++ and is only applicable to RR. |

**IBM ConTest benchmark** — excluded: no longer available.

---

## Metrics and Tool Mapping

### 1. Overhead

**Tools compared:** This tool vs RR  
**Benchmark:** DaCapo 9.12-bach  
**Measurement:** Wall-clock time in three modes — baseline (no agent), capture, replay — following the methodology in `overhead-bench/`. Report mean ± std-dev over 10 measured rounds with 3 warmup rounds discarded.

| Column | Meaning |
|---|---|
| Baseline | No agent attached |
| Capture | Agent recording synchronization events / RR recording syscalls |
| Replay | Agent enforcing recorded order / RR replaying |
| Cap/Base, Rep/Base | Overhead multipliers |

**Expected outcome:** RR overhead will be significantly higher because it records all syscalls, memory accesses, and JIT activity — not just synchronization events. This is a key differentiator for this tool.

**JPF:** Excluded from overhead comparison — JPF cannot scale to DaCapo workloads due to state space explosion.

---

### 2. Replay Determinism

**Tools compared:** This tool vs RR  
**Benchmark:** JaConTeBe  
**Measurement:** For each bug, run capture N times, then replay each captured trace. Report the fraction of replays that reproduce the original bug manifestation (same failure, same stack trace).

Also compare reproduction rate against LEAP's published results on the JaConTeBe real-world bugs (LEAP: 7/8 = 88%). Note that this is a comparison of published numbers, not a head-to-head run on the same machine.

**Failure analysis:** For each bug not reproduced, document the reason:
- Non-determinism source not instrumented (e.g. JDK-internal races, `System.currentTimeMillis()`)
- Random number / external input not captured
- Out-of-memory during replay

---

### 3. Trace Effectiveness

**Tools compared:** This tool vs RR  
**Benchmark:** DaCapo 9.12-bach  
**Measurements:**
- Trace size (bytes) per workload
- Trace size growth rate relative to workload iteration count (does it scale linearly?)

**Expected outcome:** RR traces include all syscalls, memory access records, and JVM internals — expected to be orders of magnitude larger than synchronization-event-only traces. Report the ratio explicitly.

---

### 4. Coverage / Applicability

**Tools compared:** This tool vs RR vs JPF  
**Benchmarks:** JaConTeBe + SCTBench (Java subset)  
**Measurement:** For each benchmark program, record whether each tool can run it at all, and if so whether it handles the program correctly.

| Dimension | This tool | RR | JPF |
|---|---|---|---|
| Requires Linux | No | Yes | No |
| Requires modified JVM | No | No | No |
| Scales to large programs | Yes | Yes | No (state space explosion) |
| Java-aware instrumentation | Yes | No (JVM is a black box) | Yes |

Document which JaConTeBe and SCTBench programs each tool fails on and the reason for failure. JPF is expected to fail on programs with large or unbounded state spaces.

---

## Comparison Summary Table

| Metric | This tool vs RR | This tool vs LEAP | This tool vs JPF |
|---|---|---|---|
| Overhead | Live comparison (DaCapo) | Not comparable (different hardware) | Not applicable |
| Replay determinism | Live comparison (JaConTeBe) | Published results only (JaConTeBe real-world bugs) | Not applicable |
| Trace effectiveness | Live comparison (DaCapo) | Not reported by LEAP | Not applicable |
| Coverage / applicability | Live comparison (JaConTeBe, SCTBench) | Not comparable | Live comparison (JaConTeBe, SCTBench) |

---

## Threats to Validity

- **LEAP comparison uses published numbers** from a different machine and JVM version. Reproduction rate figures are comparable; timing figures are not.
- **JaConTeBe bug availability** — confirm which bugs from LEAP's original evaluation are present in the current JaConTeBe release before finalising the reproduction rate comparison.
- **DaCapo non-determinism** — some workloads have residual timing variance. Use sufficient rounds (≥10 measured) and report std-dev. Discard runs where std-dev exceeds 20% of the mean.
- **RR overhead on JVM** — RR records the entire JVM process including GC and JIT activity. Reported overhead is for the whole process, not just application code, which inflates RR's numbers relative to a JVM-level tool. State this explicitly.
- **SCTBench Java subset** — verify each benchmark is a faithful translation of the original C/C++ program, not a simplified port, before drawing conclusions from the results.
