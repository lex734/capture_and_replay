# Schedule Replay Specification

## Goal

Reuse the current branch's causal-order capture to test whether a bug-triggering schedule from version `V1` is still applicable to a modified version `V2`.

This is schedule replay, not exact execution replay.

## Replay Model

Replay preserves:

- captured epoch order
- logical-role progression at replay boundaries
- enough inter-thread structure to reapply the bug-triggering schedule

Replay does not require:

- exact object identity
- exact values
- exact per-thread event order
- exact bytecode equivalence

Replay does not inject captured values. Value payloads may be retained for
diagnostics, but schedule coordination is driven only by replay boundaries.

## Replay Boundaries

Replay boundaries are captured synchronization and thread-utility events.

Replay-boundary identity is:

- `epoch`
- `role`
- `eventType`
- `className`
- `methodName`

Same-`eventType` boundaries within one method are intentionally collapsed.

Each epoch corresponds to exactly one replay boundary.

## Distilled Replay Artifact

The distiller:

1. reads the captured trace
2. retains only replay-relevant boundary events
3. keeps each boundary's `epoch`, `role`, `eventType`, `className`, and `methodName`
4. discards object/value equality as replay constraints

Replay-boundary records carry the raw boundary site in the trace so they can be
joined against replay-boundary metadata during distillation.

The entire thread schedule is successfully applied only if all distilled boundaries are consumed in captured order.

## Static Analysis

Static analysis is required before every replay attempt.

It is source-based, not instance-exact. Its purpose is only to detect obvious infringements of the captured replay schedule. It does not reconstruct a full schedule DAG of the modified program.

Static analysis:

- seeds dependency classes from replay-boundary `className + methodName`
- expands locally through synchronization-relevant references
- rejects only on strong evidence of obvious incompatibility

Rules:

- reject if a corresponding boundary has the opposite synchronization meaning, meaning the captured happens-before relationship is inverted
- do not reject solely because the captured boundary is absent
- if compatibility is unknown, continue to dynamic replay

Because replay identity collapses same-`eventType` boundaries within a method, static analysis must inspect all matching boundaries in that method when checking for inverse relationships.

## Dynamic Replay

If static analysis passes, dynamic replay gates threads at replay boundaries.

At each boundary:

1. resolve the current thread to a logical role
2. consult the replay coordinator
3. if the role is allowed to cross its next boundary, proceed
4. otherwise block

Blocking is cooperative and boundary-based using a shared control lock.

Threads run freely between boundaries. Extra benign local work before the next boundary is allowed.

## Outcomes

- `statically_inapplicable`: static analysis found obvious incompatibility
- `bug_reproduced`: the entire schedule was applied and the bad outcome reappeared
- `schedule_applied_bug_absent`: the entire schedule was applied and the bad outcome did not reappear
- `dynamically_inapplicable`: replay is unable to apply the captured schedule at runtime
- `dynamically_diverged`: replay points are reached, but the thread schedule has changed and coordination can no longer enforce the captured schedule
- `incomplete`: a required next boundary is never reached during replay
- `timeout`: wall-clock budget expired

## Capture-Side Change

Cross-version replay needs stable replay-boundary metadata for replay-relevant events.

The raw numeric `siteId` remains in the trace, but capture also emits a sidecar metadata table mapping:

`rawSiteId -> eventType, className, methodName`

This metadata is used by schedule distillation and later static analysis.
