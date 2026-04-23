# SCTBench Benchmark Catalog

This note summarizes the Java SCTBench programs in this repository and classifies each benchmark by:

- the concurrency bug it is intended to induce
- the main shared object, lock protocol, or concurrent data structure involved
- the failure signal used as the witness

Source root:
[`benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench>)

## Families

- Reordering and consistency bugs: `Reorder*`, `Twostage*`, `TokenRingBad`
- Wrong synchronization or wrong-lock bugs: `Wronglock*`, `BluetoothDriverBad`, `AccountBad`
- Deadlock and liveness bugs: `Deadlock01Bad`, `Carter01Bad`, `Phase01Bad`, `Sync01Bad`, `Sync02Bad`
- Broken container semantics: `WorkStealQueue`, `CircularBufferBad`, `QueueBad`, `StackBad`, `StringBufferJDK`
- Other shared-state invariant bugs: `ArithmeticProgBad`, `Lazy01Bad`, `FsbenchBad`

## Benchmarks

| Benchmark | Kind of bug induced | Shared structure involved | Failure witness |
|---|---|---|---|
| [`StringBufferJDK`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cb/StringBufferJDK.java>) | Atomicity violation and inconsistent snapshot during concurrent mutation and append | A `StringBuffer`-style mutable char buffer: `char[] value`, `count`, `valueLength` | `AssertionError` from bounds checks in `getChars` or `erase` |
| [`WorkStealQueue`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/chess/WorkStealQueue.java>) | Linearizability bug causing lost or duplicated work items | Work-stealing deque with `head`, `tail`, backing array, and a `ReentrantLock` | End-of-run invariant failure via `item.check()` |
| [`Reorder3Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Reorder3Bad.java>) | Mixed-state observation of related writes | Two shared `volatile int`s: `a`, `b` | Prints `Bug found!` then `AssertionError` |
| [`Reorder4Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Reorder4Bad.java>) | Mixed-state observation of related writes | Two shared `volatile int`s: `a`, `b` | Prints `Bug found!` then `AssertionError` |
| [`Reorder5Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Reorder5Bad.java>) | Mixed-state observation of related writes | Two shared `volatile int`s: `a`, `b` | Prints `Bug found!` then `AssertionError` |
| [`Reorder10Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Reorder10Bad.java>) | Mixed-state observation of related writes | Two shared `volatile int`s: `a`, `b` | Prints `Bug found!` then `AssertionError` |
| [`Reorder20Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Reorder20Bad.java>) | Mixed-state observation of related writes | Two shared `volatile int`s: `a`, `b` | Prints `Bug found!` then `AssertionError` |
| [`Reorder50Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/hard/Reorder50Bad.java>) | Mixed-state observation of related writes | Two shared `volatile int`s: `a`, `b` | Prints `Bug found!` then `AssertionError` |
| [`Reorder100Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/hard/Reorder100Bad.java>) | Mixed-state observation of related writes | Two shared `volatile int`s: `a`, `b` | `AssertionError` in the checker |
| [`AccountBad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/AccountBad.java>) | Invariant violation in a bank-account workflow | Shared account state: `balance`, `deposit_done`, `withdraw_done`, one `ReentrantLock` | `AssertionError` in `check_result()` |
| [`ArithmeticProgBad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/ArithmeticProgBad.java>) | Producer-consumer protocol bug yielding a wrong final total | Single-slot shared state: `num`, `total`, `flag`, two `Condition`s on one `ReentrantLock` | Final `AssertionError` after joins |
| [`BluetoothDriverBad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/BluetoothDriverBad.java>) | Shutdown/add race in a driver lifecycle protocol | Shared `Device` object with `pendingIo`, `stoppingFlag`, `stoppingEvent` | `assert !stopped` failure in `BCSP_PnpAdd` |
| [`Carter01Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Carter01Bad.java>) | Deadlock from incompatible lock ordering and spin-reacquire logic | Two `ReentrantLock`s `m` and `l`, plus lock-state flags | `Deadlock detected`, `RuntimeException`, or timeout |
| [`CircularBufferBad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/CircularBufferBad.java>) | FIFO/order violation in circular-buffer bookkeeping | Array-backed circular buffer with `first`, `next`, and one `ReentrantLock` | `assert removeLogElement() == i` |
| [`Deadlock01Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Deadlock01Bad.java>) | Classic two-lock deadlock | Two `ReentrantLock`s `a` and `b` | `RuntimeException("deadlock")` or timeout |
| [`FsbenchBad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/FsbenchBad.java>) | Invalid lock/index access in filesystem-style metadata allocation | Lock arrays `locki[]`, `lockb[]`, plus `inode[]` and `busy[]` arrays | Assertion on index bounds or bad unlock path |
| [`Lazy01Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Lazy01Bad.java>) | Forbidden combined-state observation after concurrent updates | Shared scalar `data` under one `ReentrantLock` | `AssertionError` when `data >= 3` |
| [`Phase01Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Phase01Bad.java>) | Deadlock from a lock left held across phases | Two `ReentrantLock`s `x` and `y`, especially `x` | `Deadlock detected`, `RuntimeException`, or timeout |
| [`QueueBad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/QueueBad.java>) | FIFO/order violation from broken queue index updates | Array-backed queue with `head`, `tail`, `amount`, one `ReentrantLock` | `assert dequeue(queue) == stored_elements[i]` |
| [`StackBad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/StackBad.java>) | Underflow due to bad push/pop coordination | Array-backed stack with shared `top`, `arr[]`, `flag`, one `ReentrantLock` | `assert pop(arr) != UNDERFLOW` |
| [`Sync01Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Sync01Bad.java>) | Producer-consumer deadlock caused by incorrect condition protocol | Shared `num`, `Condition`s `empty` and `full`, one `ReentrantLock` | `Deadlock detected`, `RuntimeException`, or timeout |
| [`Sync02Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Sync02Bad.java>) | Producer-consumer deadlock caused by incorrect condition/flag coordination | Shared `num`, `waiting`, `Condition`s, one `ReentrantLock` | `Deadlock detected`, `RuntimeException`, or timeout |
| [`TokenRingBad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/TokenRingBad.java>) | Ring invariant violation from inconsistent multi-variable update observation | Three token variables `x1`, `x2`, `x3` plus `AtomicBoolean` flags | `assert (x1 == x2 && x2 == x3)` |
| [`TwostageBad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/TwostageBad.java>) | Multi-stage consistency bug: reader sees stage 1 without matching stage 2 | Two related shared ints `data1Value`, `data2Value` protected by different locks | Prints `Bug found!` then `AssertionError` |
| [`Twostage100Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Twostage100Bad.java>) | Multi-stage consistency bug under more updater threads | Same two-stage shared state and two locks | Prints `Bug found!` then `AssertionError` |
| [`Wronglock1Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Wronglock1Bad.java>) | Wrong-lock bug causing lost updates | Shared counter `dataValue` protected by `dataLock` in one path and `thisLock` in another | Prints `Bug Found!` then `AssertionError` |
| [`Wronglock3Bad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/Wronglock3Bad.java>) | Wrong-lock bug causing lost updates | Shared counter `dataValue` protected by two different locks | Prints `Bug Found!` then `AssertionError` |
| [`WronglockBad`](</Users/scott/enxing_fyp/capture_and_replay/benchmark/bms/SCTBench/src/main/java/cmu/pasta/fray/benchmark/sctbench/cs/origin/WronglockBad.java>) | Wrong-lock bug causing lost updates | Shared counter `dataValue` protected by two different locks | Prints `Bug Found!` then `AssertionError` |

## Notes

- Not every benchmark uses a full-fledged concurrent data structure. Several are deliberately small litmus-style programs over shared scalars, flags, and locks.
- The `Reorder*` family all encode the same basic anomaly and vary mainly in thread counts.
- The `Wronglock*` family all encode the same wrong-lock pattern and vary mainly in contention level.
- The `Twostage*` family encodes a split-phase consistency bug where the reader can observe stage 1 without the corresponding stage 2 update.
