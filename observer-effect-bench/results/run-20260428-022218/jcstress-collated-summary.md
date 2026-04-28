# jcstress collated results

Run directory: `/home/enxing/capture_and_replay/observer-effect-bench/results/run-20260428-022218`

## Environment

- Host kernel: `Linux 6.6.87.1-microsoft-standard-WSL2`
- Runtime environment: `WSL2` on Microsoft hypervisor
- CPU: `13th Gen Intel(R) Core(TM) i7-13650HX`
- CPU topology seen by jcstress: `1 package`, `10 cores`, `2 threads/core`, `20 CPUs total`
- JVM in logs: OpenJDK `21.0.10`

Notes:
- This is not a bare-metal Linux run.
- For this analysis, that mainly means frequency differences should be treated as platform-specific and virtualization-sensitive, even when the qualitative weak-memory outcomes are still meaningful.

## Headline

- Tests with both `plain` and `capture` logs: 140
- `plain`: 29 interesting, 0 failed, 0 error
- `capture`: 28 interesting, 0 failed, 0 error
- No tests were classified as failed or error in either mode.

## VM Configuration Matrix

- The run uses the same five JVM configuration families in both modes:
- `[]` : default mixed-tier execution
- `[-Xint]` : interpreter only
- `[-XX:TieredStopAtLevel=1]` : low-tier compiled
- `[-XX:-TieredCompilation]` : non-tiered optimized compilation
- `[-XX:+StressLCM, -XX:+StressGCM, -XX:+StressIGVN, -XX:+StressCCP, -XX:+StressIncrementalInlining]` : stressed C2 optimizer
- In `capture`, each of those same families is prefixed with the trace agent:
  - `-javaagent:/home/enxing/capture_and_replay/capture/target/trace-capture-agent.jar=...`
- The logs also show `Forks per test: 1 normal, 1 stress`, so results are sampled across multiple scheduling classes and compiler states rather than a single JVM mode.

Why this matters:
- The comparison is stronger than a single `plain` versus `capture` run under one compiler mode.
- Weak-memory outcomes that survive into `capture` are surviving across interpreter, C1-like, optimized, and stressed-optimizer executions.

## Classification differences

- `plain`-only interesting: `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_08_Finals.PlainInit`

## Plain-only interesting detail

- `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_08_Finals.PlainInit`
  plain: `1, 2, 3, 0=<0.01%` (Seeing partially constructed object.)
  capture: `none`

## Forbidden Outcomes

- Declared forbidden outcomes appear in 34 tests in this run.
- Across both `plain` and `capture`, every forbidden aggregate row remained at `0` samples and `0.00%`.
- No forbidden outcome became newly reachable under the capture agent.

Representative forbidden rows that stayed at zero in both modes:
- `BasicJMM_07_Consensus.VolatileDekker`: forbidden `0, 0`
- `BasicJMM_06_Causality.AcquireReleaseGuard`: forbidden `1, 0`
- `BasicJMM_06_Causality.VolatileGuard`: forbidden `1, 0`
- `BasicJMM_06_Causality.LockGuard`: forbidden `0, 1` and `1, 0`
- `BasicJMM_05_Coherence.SameVolatileRead`: forbidden `1, 0`
- `Mutex_02_DekkerAlgorithm`: forbidden `1, 1`

Why this matters:
- The agent is not introducing correctness regressions in the stronger variants of these tests.
- At the same time, weaker variants still show the expected weak-memory outcomes:
  - `PlainDekker` and `AcqRelDekker` still produce `0, 0`;
  - `PlainReads` and `OpaqueReads` still produce `1, 0`;
  - `VolatileDekker` and the guarded causality variants keep their forbidden rows at zero.
- That pattern is a useful control: `capture` is perturbing frequencies without collapsing the distinction between weak/plain accesses and stronger synchronization.

## Interpretation

- These results do not point to a hardware bug, but they do suggest that the capture agent is still able to reproduce many outcomes whose root cause depends on weak memory behavior at the hardware/JMM boundary.
- In other words: the question is not whether the CPU is faulty; it is whether instrumentation preserves executions that rely on reorderings, visibility delays, non-SC behavior, or weak publication. On that narrower question, the answer from this run is mostly yes.
- Evidence for that:
  - the same broad set of weak-memory-style outcomes appears in both `plain` and `capture`;
  - `capture` still reproduces classic non-SC or weak-publication signatures such as Dekker failures, causality anomalies, stale-spin outcomes, and broken-publication/singleton races;
  - there are no `Failed tests` or `Error tests`, which means the agent perturbs frequency more than it changes the set of reachable behaviors.
- The strongest examples are the tests that are explicitly about memory ordering or visibility:
  - `BasicJMM_07_Consensus.AcqRelDekker`: `0, 0` appears in both modes, and is actually more frequent under `capture` (`6.83%` to `12.01%`);
  - `BasicJMM_07_Consensus.PlainDekker`: `0, 0` appears in both modes at high frequency (`19.96%` vs `15.95%`);
  - `BasicJMM_06_Causality.PlainReads` and `BasicJMM_06_Causality.OpaqueReads`: the "see y but not x" style outcome remains present under `capture`;
  - `BasicJMM_07_Consensus.VolatileDekker`: the corresponding forbidden `0, 0` outcome remains at exactly zero in both modes, which is a good control;
  - `BasicJMM_06_Causality.AcquireReleaseGuard`, `VolatileGuard`, and `LockGuard`: the stronger guarded variants keep their forbidden rows at zero in both modes, while the plain/opaque variants still expose weak outcomes;
  - `AdvancedJMM_02_*IRIW*` and related advanced JMM tests still expose their rare weak-ordering outcomes under `capture`;
  - broken singleton / broken publication tests still show their race signatures under `capture`.
- That means the capture agent is not simply serializing execution enough to remove weak-memory phenomena wholesale. It still allows many executions that reflect the underlying platform memory behavior and the Java Memory Model's allowance for racy code.
- The main caution is that `capture` clearly perturbs the distribution:
  - some weak-memory outcomes become more common;
  - some become less common;
  - one very rare outcome disappears entirely: `BasicJMM_08_Finals.PlainInit` partial construction (`26` samples in `plain`, none in `capture`).
- So the best reading is:
  - for common or moderately frequent weak-memory signatures, `capture` appears able to replicate them;
  - for extremely low-frequency edge cases, `capture` may mask them by changing timing, compilation, allocation, or thread interleavings.

## Bottom line

- If the question is "does the capture agent still reproduce outcomes that depend on hardware/JMM weakness?", this run says mostly yes.
- If the question is "does it reproduce them with unchanged probabilities?", the answer is no.
- If the question is "does it preserve every rare weak-memory manifestation?", this run also says no: the `BasicJMM_08_Finals.PlainInit` partial-construction outcome disappeared under `capture`.
- So the capture agent seems good at preserving the qualitative reachability of many weak-memory behaviors, but not at preserving exact frequencies or guaranteeing retention of the rarest ones.
- Because the run was collected under WSL2 on an Intel i7-13650HX rather than bare-metal Linux, the safest claim is about qualitative preservation of outcomes, not exact microarchitectural fidelity of their frequencies.

## Largest interesting-frequency shifts

| Test | Result | plain | capture | Note |
| --- | --- | --- | --- | --- |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_07_Consensus.AcqRelDekker` | `0, 0` | 6.83% | 12.01% | Violates sequential consistency |
| `org.openjdk.jcstress.samples.primitives.lazy.Lazy_02_BrokenNulls.NullHolder` | `null-holder, dup` | 55.86% | 60.59% | Factory is called twice! |
| `org.openjdk.jcstress.samples.primitives.lazy.Lazy_02_BrokenNulls.NullHolder` | `dup, null-holder` | 44.14% | 39.41% | Factory is called twice! |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_07_Consensus.PlainDekker` | `0, 0` | 19.96% | 15.95% | Violates sequential consistency |
| `org.openjdk.jcstress.samples.primitives.singletons.Singleton_01_BrokenUnsynchronized.Final` | `data1, data2` | 10.96% | 13.74% | Race condition. |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_03_WordTearing.BitSets` | `true, false` | 4.67% | 3.31% | Destroyed one update. |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_03_WordTearing.BitSets` | `false, true` | 4.32% | 3.27% | Destroyed one update. |
| `org.openjdk.jcstress.samples.primitives.singletons.Singleton_02_BrokenVolatile.NonFinal` | `data1, data2` | 8.63% | 7.59% | Race condition. |
| `org.openjdk.jcstress.samples.problems.racecondition.RaceCondition_01_RMW.Racy` | `250, 100, 100` | 3.31% | 2.58% | actors conflicted, actor2 won the race |
| `org.openjdk.jcstress.samples.problems.racecondition.RaceCondition_02_CheckThenReact.Racy` | `true, true` | 6.39% | 7.02% | Conflict: both actors entered the section |

## All tests with interesting outcomes

| Test | plain | capture |
| --- | --- | --- |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_01_SynchronizedBarriers` | `1, 0=<0.01%` (Whoa) | `1, 0=<0.01%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_02_MultiCopyAtomic.FencedIRIWTest` | `1, 0, 1, 0=0.00%` (Whoa) | `1, 0, 1, 0=0.00%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_02_MultiCopyAtomic.FullyFencedIRIWTest` | `1, 0, 1, 0=0.00%` (Whoa) | `1, 0, 1, 0=0.00%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_02_MultiCopyAtomic.OpaqueIRIW` | `1, 0, 1, 0=0.00%` (Whoa) | `1, 0, 1, 0=0.00%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_04_LosingUpdates.Volatiles` | `2=<0.01%; 3=<0.01%; 4=<0.01%` (Whoa) | `2=<0.01%; 3=<0.01%; 4=<0.01%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_05_MisplacedVolatile.Racy` | `0=0.00%` (Whoa) | `0=0.00%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_08_ArrayVolatility.DeclarationSite` | `1, 0=0.03%` (Whoa) | `1, 0=0.04%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_09_WrongReleaseOrder` | `1, 0=0.46%` (Whoa) | `1, 0=0.25%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_10_WrongListReleaseOrder` | `-2=0.18%; -3=0.00%; -4=0.00%` (Whoa-whoa) | `-2=0.21%; -3=0.00%; -4=<0.01%` (Whoa-whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_12_WrongAcquireReleaseOrder` | `1, 0=0.01%` (Whoa) | `1, 0=0.01%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_13_VolatileVsFinal.RealLife` | `0=0.00%` (Whoa) | `0=0.00%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_13_VolatileVsFinal.Synthetic` | `0=0.00%` (Whoa) | `0=0.00%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_14_SynchronizedAreNotFences.Synchronized` | `1, 0=0.02%` (Whoa) | `1, 0=0.06%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.advanced.AdvancedJMM_15_VolatilesAreNotFences.Volatiles` | `1, 0, 0=0.00%` (Whoa) | `1, 0, 0=0.00%` (Whoa) |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_03_WordTearing.BitSets` | `false, true=4.32%; true, false=4.67%` (Destroyed one update.) | `false, true=3.27%; true, false=3.31%` (Destroyed one update.) |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_04_Progress.PlainSpin` | `STALE=0.05%` (Test is stuck) | `STALE=0.07%` (Test is stuck) |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_04_Progress.SyncSpin` | `STALE=0.00%` (Test is stuck) | `STALE=0.00%` (Test is stuck) |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_04_Progress.VolatileSpin` | `STALE=0.00%` (Test is stuck) | `STALE=0.00%` (Test is stuck) |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_05_Coherence.SameRead` | `1, 0=0.03%` (First read seen racy value early, and the second one did not.) | `1, 0=0.02%` (First read seen racy value early, and the second one did not.) |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_06_Causality.OpaqueReads` | `1, 0=0.00%` (Seeing $y, but not $x!) | `1, 0=0.00%` (Seeing $y, but not $x!) |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_06_Causality.PlainReads` | `1, 0=0.07%` (Seeing $y, but not $x!) | `1, 0=0.06%` (Seeing $y, but not $x!) |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_07_Consensus.AcqRelDekker` | `0, 0=6.83%` (Violates sequential consistency) | `0, 0=12.01%` (Violates sequential consistency) |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_07_Consensus.PlainDekker` | `0, 0=19.96%` (Violates sequential consistency) | `0, 0=15.95%` (Violates sequential consistency) |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_08_Finals.PlainInit` | `1, 2, 3, 0=<0.01%` (Seeing partially constructed object.) | `none` |
| `org.openjdk.jcstress.samples.jmm.basic.BasicJMM_09_BenignRaces.DoubleRead` | `-1, -1=<0.01%; -1, 42=<0.01%; 42, -1=<0.01%` (Whoa) | `-1, -1=<0.01%; -1, 42=<0.01%; 42, -1=<0.01%` (Whoa) |
| `org.openjdk.jcstress.samples.primitives.lazy.Lazy_01_BrokenFactory.RacyOneWay` | `dup=0.00%; exception=0.00%` (Supplier barfed/internal error) | `dup=0.00%; exception=0.00%` (Supplier barfed/internal error) |
| `org.openjdk.jcstress.samples.primitives.lazy.Lazy_02_BrokenNulls.NullHolder` | `dup, null-holder=44.14%; null-holder, dup=55.86%` (Factory is called twice!) | `dup, null-holder=39.41%; null-holder, dup=60.59%` (Factory is called twice!) |
| `org.openjdk.jcstress.samples.primitives.lazy.Lazy_04_BrokenOneShot.RacyOneWay` | `null-holder=0.00%` (Seeing uninitialized holder!) | `null-holder=0.00%` (Seeing uninitialized holder!) |
| `org.openjdk.jcstress.samples.primitives.lazy.Lazy_04_BrokenOneShot.RacyTwoWay` | `.*, null-holder=0.00%; null-holder, .*=0.00%` (Seeing uninitialized holder!) | `.*, null-holder=0.00%; null-holder, .*=0.00%` (Seeing uninitialized holder!) |
| `org.openjdk.jcstress.samples.primitives.library.Library_01_CHM.BrokenMultimap` | `Bar, null=1.85%; Baz, null=1.82%` (One update lost.) | `Bar, null=1.76%; Baz, null=1.80%` (One update lost.) |
| `org.openjdk.jcstress.samples.primitives.rmw.RMW_01_UncontendedSuccess.Weak` | `false=0.00%` (Spurious failures are allowed) | `false=0.00%` (Spurious failures are allowed) |
| `org.openjdk.jcstress.samples.primitives.rmw.RMW_02_ContendedSuccess.Weak` | `false, false=0.00%` (Not even once) | `false, false=0.00%` (Not even once) |
| `org.openjdk.jcstress.samples.primitives.rmw.RMW_03_ConflictSameValue.Weak` | `false=0.00%` (Spurious failures are allowed) | `false=0.00%` (Spurious failures are allowed) |
| `org.openjdk.jcstress.samples.primitives.rmw.RMW_09_GAS_Effects.CAS_CAS` | `0, 0=0.00%` (Interesting) | `0, 0=0.00%` (Interesting) |
| `org.openjdk.jcstress.samples.primitives.rmw.RMW_09_GAS_Effects.CTS_CTS` | `0, 0=2.64%` (Interesting) | `0, 0=2.48%` (Interesting) |
| `org.openjdk.jcstress.samples.primitives.rmw.RMW_09_GAS_Effects.GTS_CAS` | `0, 0=0.31%` (Interesting) | `0, 0=0.37%` (Interesting) |
| `org.openjdk.jcstress.samples.primitives.rmw.RMW_10_FailureWitness.BooleanCAS` | `false, 0=0.01%` (Whoa) | `false, 0=0.01%` (Whoa) |
| `org.openjdk.jcstress.samples.primitives.singletons.Singleton_01_BrokenUnsynchronized.Final` | `data1, data2=10.96%` (Race condition.) | `data1, data2=13.74%` (Race condition.) |
| `org.openjdk.jcstress.samples.primitives.singletons.Singleton_01_BrokenUnsynchronized.NonFinal` | `data1, data2=13.22%` (Race condition.) | `data1, data2=13.01%` (Race condition.) |
| `org.openjdk.jcstress.samples.primitives.singletons.Singleton_02_BrokenVolatile.Final` | `data1, data2=7.42%` (Race condition.) | `data1, data2=6.94%` (Race condition.) |
| `org.openjdk.jcstress.samples.primitives.singletons.Singleton_02_BrokenVolatile.NonFinal` | `data1, data2=8.63%` (Race condition.) | `data1, data2=7.59%` (Race condition.) |
| `org.openjdk.jcstress.samples.primitives.singletons.Singleton_07_BrokenNonVolatileDCL.NonFinal` | `data1, null-data=0.00%; null-data, data2=0.00%` (Data races.) | `data1, null-data=0.00%; null-data, data2=0.00%` (Data races.) |
| `org.openjdk.jcstress.samples.problems.classic.Classic_02_ProducerConsumerProblem.FixedTwoProducersOneConsumer` | `0, 0, 2=0.00%` (Producers overwrote each other's item.) | `0, 0, 2=0.00%` (Producers overwrote each other's item.) |
| `org.openjdk.jcstress.samples.problems.classic.Classic_02_ProducerConsumerProblem.FlawedTwoProducersOneConsumer` | `0, 0, 2=0.42%` (Producers overwrote each other's item.) | `0, 0, 2=0.31%` (Producers overwrote each other's item.) |
| `org.openjdk.jcstress.samples.problems.classic.Classic_02_ProducerConsumerProblem.Lock` | `0, 0, 2=0.00%` (Producers overwrote each other's item.) | `0, 0, 2=0.00%` (Producers overwrote each other's item.) |
| `org.openjdk.jcstress.samples.problems.racecondition.RaceCondition_01_RMW.Racy` | `250, 100, 100=3.31%; 250, 100, 250=2.86%` (Actor conflicts) | `250, 100, 100=2.58%; 250, 100, 250=2.56%` (Actor conflicts) |
| `org.openjdk.jcstress.samples.problems.racecondition.RaceCondition_02_CheckThenReact.Racy` | `true, true=6.39%` (Conflict: both actors entered the section) | `true, true=7.02%` (Conflict: both actors entered the section) |
