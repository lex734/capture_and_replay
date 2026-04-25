# Benchmark Bug Analysis

Analysis of concurrency bugs in the SCTBench and JaConTeBe benchmark suites, focusing on whether bugs manifest at the software/JVM level or require hardware-level memory model effects (store buffering, cache coherency, out-of-order execution).

---

## SCTBench

SCTBench is a suite of synthetic concurrency benchmarks. Roughly 75% of bugs are pure software/JVM-level; the `Reorder*` family is the clearest example of hardware-level memory reordering bugs.

### Software-Level Bugs

#### Deadlocks

| Benchmark | Bug | Root Cause |
|---|---|---|
| `Carter01Bad` | Circular lock wait | Threads t1 and t2 acquire locks `m` and `l` in opposite orders |
| `Deadlock01Bad` | Circular lock wait | Non-atomic check-then-lock on locks `a` and `b` |
| `Phase01Bad` | Missing unlock | Second invocation of thread1 acquires lock `x` but the `unlock()` call is commented out |
| `Sync01Bad` | Lost notify / deadlock | Consumer never actually consumes (`num--` is commented out), so producer waits forever |
| `Sync02Bad` | Race on `waiting` flag | `waiting` is checked and set outside the condition variable's lock, so both producer and consumer can get stuck |

#### Atomicity Violations

| Benchmark | Bug | Root Cause |
|---|---|---|
| `AccountBad` | Invariant broken across lock regions | `check_result()`, `deposit()`, `withdraw()` each hold the lock individually but the composite invariant is not atomic |
| `Lazy01Bad` | Incomplete synchronization | All three threads complete updates before the checker fires, violating the `data < 3` invariant |
| `TwostageBad` / `Twostage100Bad` | Locks too fine-grained | `funcA()` updates `data1Value` and `data2Value` under separate locks; `funcB()` can observe `data1Value=1, data2Value=0` |
| `WorkStealQueue` | Double-checked locking | `push()` and `pop()` write `tail` unsynchronized on the fast path; `steal()` locks on `head` — work items can be duplicated or lost |

#### Order Violations

| Benchmark | Bug | Root Cause |
|---|---|---|
| `ArithmeticProgBad` | Unsynchronized final update | `total` is updated outside the lock after thread2 exits its loop, racing with consumer updates |
| `StackBad` | Wrong flag semantics | `flag` signals that at least one push happened, but `pop()` loop doesn't track the push count |
| `CircularBufferBad` | Wrong wrap-around index | `next = (next+1) % buffer_size` wraps to the wrong index when the buffer is full |
| `QueueBad` | Wrong wrap-around index | Tail and head wrap to `1` instead of `0` on overflow, breaking FIFO order |

#### Algorithm / Logic Bugs

| Benchmark | Bug | Root Cause |
|---|---|---|
| `FsbenchBad` | Array out-of-bounds | `NUMINODE (32) > NUMBLOCKS (26)`; `locki[i]` access can be out of bounds |

---

### Hardware-Level Bugs

These bugs can manifest via CPU store buffering, cache coherency, or out-of-order execution. They are also Java Memory Model (JMM) data races, so any JMM-conforming implementation may exhibit them.

| Benchmark | Bug | Root Cause |
|---|---|---|
| `Reorder3/4/5/10/20Bad` | Memory reordering | Two threads write `a=1; b=-1` without synchronization; readers can observe mixed states like `(a=1, b=0)` due to store buffering. This is the canonical hardware reordering pattern. |
| `BluetoothDriverBad` | Stale cached values | `stoppingFlag` and `stopped` are not `volatile`; one thread can read a stale cached value after the other thread has updated it |
| `TokenRingBad` | Mixed sync mechanisms | `AtomicBoolean.set()` does not establish happens-before for reads of plain (non-atomic) fields; checker thread can see stale `x1/x2/x3` even after flags are `true` |
| `WronglockBad` / `Wronglock1Bad` / `Wronglock3Bad` | Wrong lock protects shared variable | `funcA()` uses `dataLock`, `funcB()` uses `thisLock` on the same `dataValue` field; no mutual exclusion between the two paths — exposed to JMM reordering |
| `StringBufferJDK` (cb suite) | Nested sync inconsistency | `sb.getChars()` is called on a `StringBuffer` being concurrently erased; synchronized methods are individually safe but the composite operation is not atomic |

---

### SCTBench Summary

| Category | Count |
|---|---|
| Deadlock | 5 |
| Atomicity violation | 4 |
| Order violation | 4 |
| Algorithm / logic bug | 1 |
| **Hardware-level (memory reordering / wrong lock / missing volatile)** | **6** |

---

## JaConTeBe

JaConTeBe contains bugs extracted from real production Java libraries. **All 40 benchmarks manifest at the software/JVM level — none require hardware memory model effects.** The bugs fall into two broad categories: deadlocks and data races.

### Apache Commons DBCP (Connection Pool)

| Benchmark | JIRA | Bug Type | Description |
|---|---|---|---|
| `dbcp1` | DBCP-65 | Deadlock | Circular lock wait between `genericObjectPool.evict()` and `poolingConnection.prepareStatement()` |
| `dbcp2` | DBCP-270 | Deadlock | Circular lock wait between `pool.evict()` and `poolableConnection.close()` |
| `dbcp3` | DBCP-369 | Data race | Concurrent unsynchronized `HashMap` access: `registerNewInstance()` (write) vs. `removeInstance()` (read+remove) |
| `dbcp4` | DBCP-271 | Inconsistent synchronization | Some accessors on shared data are synchronized, others are not — creates a race window |

### Apache Derby (Database Engine)

| Benchmark | JIRA | Bug Type | Description |
|---|---|---|---|
| `derby1` | DERBY-4129 | Deadlock | Concurrent `executeQuery()` and `getBytes()` acquire locks in conflicting order |
| `derby2` | DERBY-5560 | Deadlock | `logicalConnection.close()` and `clientPooledConnection.close()` deadlock on related objects |
| `derby3` | DERBY-5561 | Atomicity violation | Non-atomic check-then-use: `nativeSQL()` checks `physicalConnection_ != null`, then another thread nullifies it via `nullPhysicalConnection()` → NPE |
| `derby4` | DERBY-764 | Deadlock | `updateLoader.modifyJar()` and `operator.unlock()` acquire locks in circular order |
| `derby5` | DERBY-5447 | Deadlock | `storedPage.releaseExclusive()` and `baseContainerHandle.close()` deadlock via observer pattern |

### Apache Groovy (Language Runtime)

| Benchmark | JIRA | Bug Type | Description |
|---|---|---|---|
| `groovy1` | GROOVY-3495 | Data race | Multiple threads load the same class concurrently via `RootLoader.loadClass()` without sync → `LinkageError` (duplicate class definition) |
| `groovy2` | GROOVY-4736 | Deadlock | File writing (`writeFile()`, synchronized) deadlocks with class loading/cache clearing |
| `groovy3` | GROOVY-5198 | Data race | Concurrent enum type conversion shares mutable state → `MissingMethodException` |
| `groovy4` | GROOVY-6456 | Data race | Shared `Matcher` state in `applyResourceNameMatcher()` accessed from multiple threads → `StringIndexOutOfBoundsException` |
| `groovy5` | GROOVY-6068 | Data race | Multiple threads call `AntBuilder.nodeCompleted()`, modifying `System.in` without synchronization |
| `groovy6` | GROOVY-4292 | Data race / infinite loop | Race in `ClassHelper` weak reference cache when multiple threads simultaneously cache generated class objects |

### Apache Log4j (Logging)

| Benchmark | Bug ID | Bug Type | Description |
|---|---|---|---|
| `log4j1` | #44032 | Data race | `ThrowableInformation.getThrowableStrRep()`: array read races with array modification → null element |
| `log4j2` | #41214 | Deadlock | RootLogger lock and per-class logger locks acquired in inconsistent order during nested logging calls |
| `log4j3` | #54325 | Data race | `AppenderAttachableImpl.removeAllAppenders()`: concurrent iteration and modification → `ArrayIndexOutOfBoundsException` |
| `log4j4` | #38137 | Lost notify / deadlock | `AsyncAppender`: all threads fill buffer and wait, but no thread can notify the dispatcher → permanent deadlock |
| `log4j5` | #50463 | Lost notify / deadlock | Similar to log4j4: exception kills dispatcher thread, leaving waiting threads blocked forever |

### Apache Lucene (Search Engine)

| Benchmark | JIRA | Bug Type | Description |
|---|---|---|---|
| `lucene1` | LUCENE-2783 | Deadlock | `IndexWriter` update threads and `IndexReader` search threads deadlock during simultaneous optimize/commit and read |
| `lucene2` | LUCENE-1544 | Deadlock | `IndexWriter.addIndexes()` and optimize deadlock when multiple threads contend on index locks |

### Apache Commons Pool (Object Pool)

| Benchmark | JIRA | Bug Type | Description |
|---|---|---|---|
| `pool1` | POOL-120 | Atomicity violation | `evict()` calling `ensureMinIdle()` races with `borrowObject()` on shared pool counters; check-and-increment is non-atomic |
| `pool2` | POOL-146 | Lost notify / deadlock | Two threads borrow with different keys; both wait on a shared condition that is never properly notified |
| `pool3` | POOL-149 | Lost notify / deadlock | Thread1 borrows and invalidates an object; Thread2 waits for borrow but the required notification never arrives |
| `pool4` | POOL-162 | Lost notify / deadlock | An interrupted waiting thread consumes a notification meant for another thread; later threads wait forever |

---

### JaConTeBe Summary

| Category | Count |
|---|---|
| Deadlock (circular lock order) | 12 |
| Lost notify / wait-notify deadlock | 6 |
| Data race (missing synchronization) | 15 |
| Atomicity violation (check-then-act) | 4 |
| Inconsistent synchronization | 1 |
| **Requires hardware memory effects** | **0** |

---

## Cross-Suite Comparison

| Property | SCTBench | JaConTeBe |
|---|---|---|
| Source | Synthetic benchmarks | Real production libraries |
| Total benchmarks | ~20 | 40 |
| Hardware-level bugs | Yes (`Reorder*`, `Wronglock*`, etc.) | None |
| Deadlocks | Yes (simple 2-lock patterns) | Yes (complex multi-subsystem) |
| Bug discovery | Designed to stress test tools | Extracted from real JIRA issues |
| Reproducibility | High (minimal reproduction) | Moderate (depends on library internals) |
