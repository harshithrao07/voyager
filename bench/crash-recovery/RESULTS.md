# Crash-recovery benchmark results — 2026-08-03

## Verdict

The default recovery path passed the requested attempt-level recovery criteria
in all five qualifying trials:

- 1,000/1,000 executions reached a terminal state.
- Zero execution scopes remained nonterminal.
- Every external invocation matched one persisted started attempt; no attempt
  ID was invoked twice.
- All 1,000 submitted execution IDs remained present and every execution kept
  persisted scope/state trace rows.

The stronger business-operation exactly-once property did **not** hold. Across
500 logical Judge0 Task states there were 773 persisted attempts/invocations,
or 273 re-attempted calls. A no-crash control also produced 48 re-attempts for
100 logical Task states, showing that most are caused by the benchmark's
12-second ASL Task timeout and retry policy under a single Judge0 worker, not by
SIGKILL alone. External side effects still need a stable operation idempotency
key.

## Configuration

All reported numbers use the production defaults; no accelerated override was
run.

| Setting | Value |
|---|---:|
| Queued attempt timeout | 300,000 ms |
| Queued watchdog poll | 60,000 ms |
| Running attempt timeout | 600,000 ms |
| Running watchdog poll | 60,000 ms |
| Stale scope threshold | 60,000 ms |
| Scope recovery poll | 1,000 ms |

Phase 1, the single-`Wait` SIGKILL smoke test, passed with final status
`SUCCEEDED`.

## Five qualifying trials

`Arm` is `Wait suspended / Task suspended / partially settled compounds` at
SIGKILL. Latencies are seconds from the restart command to persisted execution
completion. `Ledger` is actual external calls / persisted started attempts,
combining webhook and Judge0 calls.

| Trial | Slow wait | Arm | Terminal result | Drain | p50 | p95 | p99 | Ledger | Re-attempted logical Tasks |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A1 | 30 s | 80 / 76 / 1 | 198 succeeded, 2 failed | 609.898 | 429.734 | 592.153 | 603.637 | 457 / 457 | 59 |
| A3 | 30 s | 80 / 76 / 2 | 200 succeeded | 503.464 | 383.922 | 491.668 | 501.067 | 451 / 451 | 51 |
| B1 | 120 s | 80 / 80 / 1 | 200 succeeded | 552.008 | 411.633 | 545.838 | 550.113 | 396 / 396 | 56 |
| B2 | 120 s | 80 / 80 / 33 | 199 succeeded, 1 failed | 546.191 | 402.829 | 534.227 | 542.637 | 395 / 395 | 56 |
| B3 | 120 s | 80 / 80 / 37 | 200 succeeded | 527.864 | 380.084 | 514.812 | 525.771 | 391 / 391 | 51 |

Restart-to-drain was 503.464–609.898 seconds, with mean 547.885 seconds and
median 546.191 seconds. Mean per-run p50/p95/p99 recovery latency was
401.640/535.740/544.645 seconds.

The initial cohort ran five times. Its 30-second waits made three crash points
drift out of the required mixed suspension before arming, so they were retained
as diagnostic trials but excluded from the five-trial table. The deterministic
120-second cohort then ran three times and armed correctly in all three. Across
all eight completed crash trials, 1,600/1,600 executions became terminal and
zero scopes remained stuck.

## Additional findings

- Three qualifying executions failed with `States.Timeout` or
  `States.BranchFailed` after the Judge0 Task exhausted its ASL timeout retry.
- Killed runs left 12–18 `workflow_function_invocations` rows in `RUNNING` per
  trial even after their workflows were terminal. They do not block execution
  scopes, but the invocation ledger needs an abandoned/reconciled terminal
  status.
- The nested deadline watchdog repeatedly throws `LazyInitializationException`
  while navigating a lazy parent `ExecutionScope` after restart. Other recovery
  paths still drained the frontier, but deadline enforcement for nested
  Parallel/Map scopes is not trustworthy until that transaction/loading defect
  is fixed.

## Recommended follow-up

1. Use a stable logical operation ID (`StateExecutionId`) as the external
   idempotency key; retain attempt ID for observability.
2. On restart, reconcile persisted Judge0 tokens and existing `RUNNING`
   invocation rows before creating a new logical attempt.
3. Mark abandoned invocation rows terminal and fix the nested-scope deadline
   watchdog's lazy-loading boundary.
4. Add a no-crash baseline to the normal benchmark command and tune the ASL
   timeout or Judge0 worker capacity so timeout retries do not obscure the
   crash-specific signal.
5. Only then run a separately labelled accelerated profile for shorter queued
   timeout/watchdog intervals and compare it with this default baseline.
