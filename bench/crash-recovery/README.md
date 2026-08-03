# Crash-recovery benchmark

This benchmark keeps the single durable `Wait` recovery smoke test as phase 1,
then runs a mixed 200-execution SIGKILL benchmark against the local Docker
stack.

## Workload

- 80 root `Wait` executions
- 80 Judge0 `Task` executions using a Python function that sleeps for 3 seconds
- 20 `Parallel` executions with a completed fast branch, a suspended slow
  branch, and a Judge0 branch
- 20 inline `Map` executions with completed fast iterations and suspended slow
  iterations

The slow waits are 120 seconds so the crash point remains stable while 200
starts are accepted. The harness will only count a run as passed when its
pre-crash database snapshot contains the Wait and Task suspensions plus at
least one compound scope with both terminal and nonterminal children.

Webhook calls opt in to Voyager execution-context headers and are recorded by
`counter-server.mjs`. Judge0 calls are reconciled through
`workflow_function_invocations.state_execution_attempt_id`. Logical Task
retries are reported separately from repeated invocations of the same attempt.

## Run

From the repository root with the normal Voyager Docker stack healthy:

```powershell
.\bench\crash-recovery-test.ps1
```

Defaults are five runs, 200 executions per run, a 10-second killed interval,
and a 900-second drain budget. Starts are sent as four concurrent batches of
50 so all 200 executions coexist without overloading the HTTP listener.

Useful parameters:

```powershell
.\bench\crash-recovery-test.ps1 `
  -Runs 5 `
  -TotalExecutions 200 `
  -ArmBudgetSeconds 90 `
  -SubmissionBatchSize 50
```

Results are written beneath `bench/results/crash-recovery/<session-id>/`:

- `phase1.json`
- `summary.json`
- `run-N/pre-crash.json`
- `run-N/summary.json`
- `run-N/execution-recovery-latencies.csv`
- `run-N/counter-invocations.json`

The process exits nonzero when any required criterion fails. Logical
re-attempts are diagnostic and do not count as duplicate invocations when each
persisted attempt ID appears exactly once in the external ledger.

No recovery-timing overrides are set by this harness. It records the repository
defaults in every session summary.
