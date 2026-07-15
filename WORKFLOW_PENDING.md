# Workflow Runtime: Pending Work

This file tracks the remaining work for the JSONata-only ASL workflow
implementation. The legacy Job runtime has been removed; all new scheduling
and execution work should use `Workflow`, `WorkflowDefinition`, and
`WorkflowExecution`.

## Remaining work summary

This is the authoritative list of unfinished work. Detailed implementation
history remains in the phase sections below.

### Priority 1: Runtime correctness and recovery

- [x] Generalize Task, Parallel, and Map retry/catch processing behind one
  compound error-handling model. Parallel and Map now share a single
  `WorkflowInterpreter.resolveCompoundFailure` path (re-fork retry derived from
  prior generation errors, `applyCompoundCatch`, terminal `failState`
  fallthrough); `resolveParallelFailure` is a thin adapter that only supplies
  the `States.BranchFailed` error name. Task retains its attempt-based retry
  shape (a retry creates a new `StateExecutionAttempt`) but shares the same
  `AslRetryResolver`/`AslCatchResolver`/`AslErrorMatcher` decision layer.
- [x] Complete recovery coverage for every stale `RUNNING` scope shape,
  including post-Task transitions, compound joins/progress, and lost child
  settlement notifications.
- [x] Add durable restart integration tests for scheduled startup,
  Task completion, Parallel joins, and Map joins.
- [x] Add simultaneous multi-node claim tests for scheduling, stale-scope
  recovery, Parallel joins, and Map concurrency windows.
- [ ] Add database constraints and indexes that materially protect runtime
  invariants after the final production schema is agreed. Deferred
  intentionally: the workflow entities already declare the invariant unique
  constraints (`scope_path`, `execution_scope_id+sequence_number`,
  `state_execution_id+attempt_number`, `workflow_id+run_number`,
  `workflow_id+scheduled_for`) and indexes covering the hot claim/watchdog
  queries (`status,available_at`, `status,timeout_at`,
  `status,heartbeat_deadline_at`, `status,wake_at`, `status,retry_at`, etc.).
  No further constraint is added now because speculative additions risk
  breaking idempotent re-fork/retry flows; revisit when the production schema
  is frozen (Phase 11).
- [x] Add configurable UTF-8 byte limits for persisted workflow/state input
  and output, Task arguments/results, error details, and variables. Oversized
  runtime data fails terminally with `States.DataLimitExceeded`.
- [x] Add opt-in retention and cleanup for completed execution trees.
  `WorkflowExecutionRetentionService` atomically claims bounded batches of
  old `SUCCEEDED`/`FAILED`/`CANCELED`/`TIMED_OUT` executions with
  `FOR UPDATE SKIP LOCKED`, deletes linked function invocations, attempts,
  state visits, scopes, and executions in foreign-key order, and rolls the
  entire batch back on failure. Active/recent executions are excluded. The
  scheduled cleanup is disabled by default and has configurable age, batch,
  initial-delay, and poll-delay settings. Unit and real-PostgreSQL tests cover
  deletion, idempotency, active/recent preservation, and simultaneous nodes.

### Priority 2: Remaining product and ASL decisions

- [x] Manual-run policy decided: `PAUSED` workflows may be run manually
  (pause suspends only the cron schedule, not ad-hoc runs); `DRAFT` and
  `ARCHIVED` remain blocked. The scheduler still permits `ACTIVE` only. Enforced
  by per-caller status sets in `WorkflowExecutionService`
  (`MANUAL_RUNNABLE_STATUSES` = {ACTIVE, PAUSED};
  `SCHEDULED_RUNNABLE_STATUSES` = {ACTIVE}).
- [ ] Decide whether to support ASL Distributed Map. Current runtime supports
  only `ProcessorConfig.Mode: INLINE` and rejects `DISTRIBUTED`.
- [x] Persist/reconstruct `$states.context.Map.Item.Value` inside an iteration.
  The raw array element is now stored on a durable `execution_scopes.item_value`
  jsonb column at fork and reconstructed in `WorkflowInterpreter.contextObject`,
  so `.Value` (and `.Index`) survive a restart.
- [x] Task cancellation is cooperative only (decided). Cancellation marks
  execution/scopes/states/attempts CANCELED and absorbs late results
  idempotently, but does not interrupt an in-flight handler or roll back its
  side effects. Contract documented on `WorkflowExecutionCancellationService`.

### Priority 3: Task-resource platform

- [x] Replaced the fixed `TaskResourceRouter` switch with a `TaskResource` SPI:
  each resource is a Spring bean (`SchedulerEmailTaskResource`,
  `SchedulerWebhookTaskResource`, `McpTaskResource`) selected by
  `supports(URI)`; the router injects `List<TaskResource>` and is not edited to
  add resources. Shared argument binding/validation lives in
  `TaskPayloadMapper`.
- [x] Defined a stable error vocabulary in `TaskResourceErrors`
  (`Scheduler.Webhook.{Timeout,ClientError,ServerError}`,
  `Scheduler.Email.SendFailed`, `Mcp.{ToolNotFound,ToolFailed}`, plus the ASL
  built-ins `States.Permissions`/`States.TaskFailed`), thrown via
  `TaskResourceException` instead of leaking Java class names.
- [x] Preserve structured resource error details: `TaskResourceException`
  carries an optional detail node (e.g. webhook `{statusCode, body}`,
  truncated) that the worker serializes into the cause surfaced to
  `$states.errorOutput.Cause`.
- [x] Map authorization failures to `States.Permissions`: webhook HTTP 401/403
  and MCP trust-level/untrusted rejections.
- [x] Resource-specific error-mapping tests for email, webhook, and MCP
  (timeout, permissions, client/server error, not-found, validation). Duplicate
  delivery and cooperative cancellation remain enforced/owned by the worker
  claim and the cancellation contract (covered by their existing tests), not the
  resource layer.
- [ ] Add workflow-runtime cleanup as a Task resource only if an explicit
  workflow use case requires it. (Won't-do unless a use case needs it.)

### Priority 4: Operations and deployment

- [ ] Add workflow metrics for execution states, Task queue latency and
  duration, retries, catches, waits, Parallel branches, and Map iterations.
- [ ] Add structured runtime logs containing workflow, execution, scope, state,
  and attempt identifiers.
- [ ] Add workflow-specific health indicators for Task dispatch and stalled
  executions. Existing Kafka and Redis health indicators are infrastructure
  checks only.
- [ ] Add administrative queries or APIs for stuck scopes and attempts.
- [ ] Propagate tracing context through Kafka Task messages.
- [ ] Define alerts for stale queued/running attempts and overdue deadlines.
- [ ] Align the production database schema with the final workflow entities,
  indexes, foreign keys, enum values, and removed legacy tables.
- [ ] Validate production `hibernate.ddl-auto=validate` against an existing
  database.
- [ ] Decide how legacy Job data is archived or discarded.
- [ ] Test forward deployment and rollback compatibility against an existing
  database.
- [ ] Document supported upgrade, rollback, and mixed-version behavior.

## Current baseline

Implemented:

- Workflow creation, immutable definition revisions, and revision activation.
- ASL definition validation with JSONata expression parsing.
- Runtime support for `Pass`, `Task`, `Choice`, `Wait`, `Succeed`, and `Fail`.
- Durable workflow, scope, state, and state-attempt persistence.
- Kafka dispatch for Task attempts with the database as source of truth.
- Task retries and catches, including exponential backoff and `FULL` jitter.
- Task `TimeoutSeconds`, `HeartbeatSeconds`, heartbeat renewal, and watchdogs.
- JSONata `Arguments`, `Assign`, `Output`, `$states.result`, and
  `$states.errorOutput`.
- Manual workflow execution.
- Durable Wait resumption.
- Playwright browser E2E coverage for builder creation, manual JSONata ASL
  template import, workflow-list persistence, immutable revision creation, and
  creator-to-runtime execution through the visible frontend. The execution UI
  now starts runs with validated JSON input, polls persisted execution detail,
  renders scopes/states/Task attempts, and cooperatively cancels active runs.
  Recurring coverage creates and activates a scheduled workflow, observes its
  scheduler-created execution in the UI, proves Pause suppresses new runs, and
  proves Resume recalculates scheduling and creates a later run. Test-created
  workflows are canceled when necessary and archived after each scenario. The
  browser suite also exercises server-backed execution status, revision,
  trigger, and exact ID/run filters through the visible execution UI.

- Nested execution-scope foundation (Phase 1), `Parallel` runtime (Phase 2),
  and core `Map` runtime (Phase 3).

The supported inline `Map` feature set is `Items`, `ItemSelector`,
`MaxConcurrency`, and an inline `ItemProcessor`. `ItemReader`, `ItemBatcher`,
`ResultWriter`, and the tolerated-failure thresholds were intentionally removed
and are now rejected at activation as `RUNTIME_SUPPORT` issues.
`ProcessorConfig.Mode: DISTRIBUTED` remains intentionally unsupported pending
the product decision above.

## Phase 1: Nested execution-scope foundation

Status: COMPLETE. This was the prerequisite for the Parallel and Map compound
state executors implemented in Phases 2 and 3.

- [x] Extend `AslDefinitionNavigator` to resolve the nested machine owned by:
  - a Parallel branch scope (parent machine -> owner state -> `Branches[index]`);
  - a Map iteration scope (parent machine -> owner state -> `ItemProcessor`).
  Resolution walks the parent chain, so arbitrarily nested machines resolve.
- [x] Persist enough nested-machine identity on `ExecutionScope` to resolve its
  machine deterministically after a restart. Already durable on the entity:
  `scope_type`, `parent_scope_id`, `owner_state_name`, `branch_index`,
  `item_index`, and the unique `scope_path`. No schema change was required.
- [x] Change scope completion so completing a child scope does not mark the
  entire `WorkflowExecution` as succeeded (`completeScope` is root-aware).
- [x] Change child-scope failure propagation so it reports failure to its owning
  compound state rather than immediately failing the root workflow
  (`failScope` is root-aware; QUEUED/WAITING transitions are gated to root).
- [x] Add parent/child coordination rules in `ExecutionScopeCoordinator`:
  - detect all children complete (`settle`);
  - detect a failed child (`settle` reports first failure in index order);
  - cancel unfinished siblings safely (`cancelUnfinishedChildren`);
  - resume the parent scope exactly once (`resumeParentIfReady`, guarded by the
    WAITING status under a pessimistic lock).
- [x] Add idempotent child-scope creation so scheduler or worker retries cannot
  create duplicate branches or iterations (creation is keyed on `scope_path`).
- [x] Define variable inheritance and isolation:
  - child scopes receive a snapshot of visible outer variables
    (`inheritedVariables`);
  - branches and iterations cannot see sibling assignments (separate rows);
  - inner assignments do not overwrite outer variables (separate rows);
  - child output is the only value returned to the parent (`settle` exposes
    child `output` only, never child variables).

## Phase 2: Parallel state runtime

Status: COMPLETE. Implemented in the interpreter (`advanceParallel` /
`forkParallel` / `joinParallel` / `resolveParallelFailure`) rather than a
separate `StateExecutor`, because a compound state must interact with scope
persistence and the coordinator. The driver (`WorkflowExecutionRunner`) is now a
multi-scope work-list that fans branches out and resumes the parent on join.

- [x] Enable `Parallel` in runtime capability validation
  (`AslRuntimeCapabilityValidator` and the machine validator) now that the
  behavior is complete.
- [x] Evaluate Parallel `Arguments`; otherwise use the state input.
- [x] Create one `PARALLEL_BRANCH` scope per branch
  (`ExecutionScopeCoordinator.forkBranch`).
- [x] Start every branch independently using its nested `StartAt`.
- [x] Execute branches concurrently through the existing durable interpreter
  (each branch is an independent scope driven by `advance`; branch Tasks
  dispatch and run in parallel via the existing Kafka path).
- [x] Keep the parent state and scope waiting until all branches finish
  (parent WAITING with no wake time; resumed only by child settlement).
- [x] Collect branch outputs in declaration order.
- [x] Use the collected array as `$states.result`.
- [x] Apply Parallel `Assign` and `Output` with same-entry semantics.
- [x] Support `Next` and `End`.
- [x] Convert an unhandled branch failure into `States.BranchFailed`.
- [x] Cancel unfinished sibling branches after an unhandled branch failure
  (`ExecutionScopeCoordinator.failParentForChild`); a late Task result on a
  canceled branch is absorbed (`WorkflowInterpreter.abandonAttempt`).
- [x] Implement Parallel `Retry` without creating Task attempts (re-forks a new
  generation; failure count derived from prior fork generations).
- [x] Implement Parallel `Catch`, including `$states.errorOutput`.
- [x] Integration tests over real Postgres: branch-order, branch failure +
  sibling cancellation (mixed Fail/Wait branches), catch, and retry re-fork
  (`ParallelWorkflowIntegrationTest`). Duplicate-resume and restart are covered
  structurally by path-keyed idempotent forks and DB-as-source-of-truth;
  dedicated durable restart and simultaneous-node tests now cover the join.

## Phase 3: Core Map state runtime

Status: COMPLETE. Implemented in the interpreter (`advanceMap` / `forkMap` /
`progressMap` / `joinMap` / `resolveMapFailure`), reusing the Phase 1/2
coordinator and the multi-scope driver. Iterations run in a sliding window
bounded by MaxConcurrency, within one fork generation per attempt.

- [x] Resolve `Items`; when omitted, require the state input to be an array.
- [x] Require the result of a JSONata `Items` expression to be an array
  (else `States.QueryEvaluationError`).
- [x] Evaluate `ItemSelector` for each element.
- [x] Populate `$states.context.Map.Item.Index` and `.Value` for `ItemSelector`;
  `.Index` is also exposed inside iterations from the scope's item index.
- [x] Persist or reconstruct `$states.context.Map.Item.Value` inside each
  iteration after restart. Durable `execution_scopes.item_value` column set at
  fork; reconstructed in `contextObject`.
- [x] Create one isolated `MAP_ITERATION` scope per selected item
  (`ExecutionScopeCoordinator.forkIteration`).
- [x] Execute the `ItemProcessor` nested machine for each iteration.
- [x] Collect outputs in original item order (sorted by item index in `settle`).
- [x] Evaluate `MaxConcurrency` and enforce both:
  - the positive workflow-declared limit (0 = unlimited);
  - a configurable lower platform safety limit
    (`scheduler.workflow.map-max-concurrency`).
- [x] Apply Map `Assign`, `Output`, `Next`, and `End`.
- [x] Implement Map `Retry` (re-run as a new generation) and `Catch`.
- [x] Make iteration creation and parent resumption idempotent (path-keyed
  forks; ready-if-WAITING parent resume; wait-scheduler poll backstop via
  `scheduler.workflow.map-poll-delay-ms` recovers lost wakeups and restarts).
- [x] Integration tests over real Postgres (`MapWorkflowIntegrationTest`):
  items + ItemSelector + ordered collection, MaxConcurrency=1 windowing,
  iteration failure propagation, Catch, Retry re-run, and advanced-feature
  runtime rejection.
- [x] Add dedicated multi-node concurrency and durable restart Map tests.

## Phase 4: Advanced Map features

Status: REMOVED (intentionally not supported). `ItemReader`, `ItemBatcher`,
`ResultWriter`, `ToleratedFailureCount`, and `ToleratedFailurePercentage` were
implemented and then removed to keep the Map runtime to a single
PROCESS pipeline. They are now rejected at activation as `RUNTIME_SUPPORT`
issues (`MAP_FEATURE_RUNTIME_UNSUPPORTED`), the `StateExecutionAttempt.kind`
TASK/READER/WRITER discriminator (and the attempt-level `resource` column) were
dropped, and `completeTaskSuccess`/`completeTaskFailure` no longer branch on
attempt kind.

- [removed] `ItemReader` / `ReaderConfig.MaxItems`.
- [removed] `ItemBatcher` (`BatchInput`, `MaxItemsPerBatch`,
  `MaxInputBytesPerBatch`).
- [removed] `ResultWriter` and its persisted result description.
- [removed] `ToleratedFailureCount` / `ToleratedFailurePercentage`. Any unhandled
  iteration failure now fails the Map (subject to its own `Retry`/`Catch`).
- [removed] Map errors `States.ItemReaderFailed`, `States.ResultWriterFailed`,
  `States.ExceedToleratedFailureThreshold` (no longer producible or accepted in
  `ErrorEquals`).
- [x] Supported `ProcessorConfig`: only `Mode: INLINE` (the default). Any other
  mode is rejected at activation and at runtime (`States.Runtime`).

The supported inline Map is `Items` (or an array state input), `ItemSelector`,
`MaxConcurrency`, and an inline `ItemProcessor`. Covered end to end by
`MapWorkflowIntegrationTest`.

## Phase 5: Workflow scheduling

Status: COMPLETE for the durable scheduling path.

- [x] Claim due ACTIVE workflows atomically with `FOR UPDATE SKIP LOCKED`.
- [x] Create one `WorkflowExecution` pinned to the active definition, set its
  `scheduledFor`, and create the root scope in the same transaction that
  advances `nextRunAt`.
- [x] Calculate the next occurrence from the claimed scheduled time in the
  workflow timezone, preventing schedule drift.
- [x] Preserve occurrence uniqueness through
  `(workflow_id, scheduled_for)`.
- [x] Start persisted PENDING executions through a separate atomic root-scope
  claim. This recovers a process failure after materialization but before the
  interpreter starts.
- [x] Recover stale startup claims when the workflow execution is still
  PENDING.
- [x] Missed-run behavior is chronological catch-up: each polling pass creates
  at most one missed occurrence per claimed workflow, then subsequent polls
  continue from the next occurrence.
- [x] PostgreSQL integration tests cover due-workflow filtering and pending-root
  claiming.
- [x] Add simultaneous multi-node schedule-claim and durable startup-restart
  tests.

## Phase 6: Machine timeout

Status: COMPLETE.

- [x] A workflow deadline watchdog polls root and nested machine deadlines.
- [x] Root executions are locked and atomically transitioned from a
  non-terminal status to `TIMED_OUT`.
- [x] Active scopes, state visits, and attempts are transitioned consistently
  with `States.Timeout`; waits and heartbeat/deadline fields are cleared.
- [x] Late or duplicate Task results cannot advance a timed-out scope or
  workflow.
- [x] Pending Parallel branches, Map iterations, retries, waits, readers,
  writers, and Task attempts in a timed-out subtree stop being driven.
- [x] Root-machine timeout terminates the workflow. Nested branch/iteration
  timeout fails that child machine and resumes the owning Parallel/Map boundary
  for its normal ASL error handling.
- [x] Nested deadlines are reconstructed durably from `scope.startedAt` plus the
  immutable nested machine's `TimeoutSeconds`; no additional schema column is
  required.
- [x] Unit and PostgreSQL integration tests cover deadline candidate selection,
  root cascade, nested-parent resumption, and late Task completion.

## Phase 7: Workflow lifecycle and APIs

- [x] Get one workflow execution.
- [x] List executions for a workflow with bounded pagination and server-side
  status, definition revision, manual/scheduled trigger, and exact execution
  ID/run-number filters.
- [x] Return execution scopes, ordered state visits, and attempts for
  inspection, including nested Parallel/Map scope identity and persisted JSON
  input/output/error details.
- [x] List workflows with bounded pagination and status/name filters.
- [x] Update workflow metadata without changing the immutable ASL definition:
  - name;
  - priority;
  - cron expression, including explicit schedule removal;
  - timezone;
  - maximum attempts.
- [x] Add explicit lifecycle operations:
  - [x] activate through immutable revision activation;
  - [x] pause;
  - [x] resume;
  - [x] archive.
- [x] Pausing and archiving clear `nextRunAt`; resuming and revision activation
  recalculate it from the current time in the workflow timezone. Existing
  executions continue independently.
- [x] Recalculate `nextRunAt` when cron/timezone metadata changes for ACTIVE
  workflows; PAUSED and ARCHIVED workflows remain unscheduled.
- [x] Cancel a workflow execution atomically and cascade cancellation to active
  scopes, state visits, and attempts. Cancellation is idempotent, queued work
  can no longer be claimed, late worker results are ignored, and existing
  succeeded/failed/timed-out executions remain unchanged.
- [x] Manual execution is allowed for `PAUSED` workflows; the scheduler still
  requires `ACTIVE`. `DRAFT` and `ARCHIVED` remain blocked. Enforced by
  per-caller status sets in `WorkflowExecutionService`.
- [x] Add optimistic concurrency through `workflows.version`; metadata PATCH
  requires `expectedVersion` and stale edits return a conflict.

Definition editing must continue creating a new immutable
`WorkflowDefinition` revision; existing executions remain pinned to their
original revision.

## Phase 8: Runtime correctness and recovery

- [x] Generalize retry/catch handling so Task, Parallel, and Map use one compound
  error-handling model. Parallel and Map share
  `WorkflowInterpreter.resolveCompoundFailure`; Task keeps its attempt-based
  retry shape but shares the same resolver/matcher decision layer.
- [x] Make all terminal completion methods idempotent, including duplicate
  worker results arriving after success, failure, retry scheduling, catch,
  timeout, cancellation, and Parallel/Map settlement. Duplicate attempt
  callbacks reconstruct the already-persisted interpreter outcome rather than
  reapplying transitions.
- [x] Complete workflow-level recovery for every safely replayable scope left
  `RUNNING` after a process failure:
  - a completed simple/Task transition with a persisted next-state cursor;
  - a runnable Parallel/Map join or progress pass;
  - a terminal child whose parent-settlement notification was lost.
  Active Task attempts and active compound children remain excluded because
  their existing worker/watchdog or child-settlement paths own recovery.
- [x] Recover the post-Task completion crash window: if
  `completeTaskSuccess`/`completeTaskFailure` commits the attempt result, state
  transition, and next `currentStateName`, but the process crashes before
  `WorkflowExecutionRunner.resume`, a scheduler must claim the stale runnable
  scope and resume it from the persisted next state without executing the
  completed Task again.
  - [x] Implemented by `StaleWorkflowScopeRecoverySchedulerService`, which
    atomically claims stale `RUNNING` scopes only when their latest persisted
    state visit is `SUCCEEDED`.
- [x] Define behavior when Kafka dispatch succeeds but the producer response is
  lost: keep the attempt `QUEUED`, record the uncertain producer result, and
  allow only the stale-queue watchdog to return it to `PENDING` for
  at-least-once republishing. Duplicate Kafka records remain harmless because
  workers atomically claim only `QUEUED -> RUNNING`.
- [x] Verify queued-attempt and running-attempt watchdog races.
- [ ] Add database constraints where they materially protect runtime invariants.
- [x] Add payload-size limits for persisted input, output, arguments, results,
  variables, and error details.
- [x] Add retention/cleanup for terminal executions, scopes, state visits,
  attempts, and linked function invocations. Cleanup is opt-in, bounded,
  transactional, restart-safe, and multi-node safe through locked candidate
  claims with `SKIP LOCKED`.

## Phase 9: Task-resource improvements

Currently supported resources:

- `voyager://send-email`
- `voyager://webhook`
- `mcp://serverId/toolName`

Pending:

- [x] `TaskResource` SPI registry replaces the fixed router switch.
- [x] Resource failures classified into stable error names
  (`TaskResourceErrors`).
- [x] Structured resource error details preserved in `Cause`/error output via
  `TaskResourceException.detail()`.
- [x] Permissions errors mapped to `States.Permissions` (webhook 401/403, MCP
  trust rejection).
- [x] Task cancellation is cooperative only (decided); it does not interrupt
  in-flight handlers. Contract documented on
  `WorkflowExecutionCancellationService`.
- [x] Resource-specific error-mapping tests added (webhook/email/MCP).
- [ ] Reintroduce a cleanup Task only if it targets workflow runtime data; the
  legacy execution-log cleanup resource was intentionally removed.
  (Won't-do unless a use case needs it.)

## Phase 10: Observability and operations

- [ ] Workflow metrics:
  - executions created, running, waiting, succeeded, failed, timed out;
  - Task queue latency and execution duration;
  - retries and catches;
  - Wait duration;
  - Parallel branch and Map iteration counts.
- [ ] Structured logs containing workflow, execution, scope, state, and attempt IDs.
- [ ] Health indicators for workflow Task dispatch and stalled workflow execution.
- [ ] Administrative visibility into stuck scopes and attempts.
- [ ] Tracing propagation through Kafka Task messages.
- [ ] Alerts for stale queued/running attempts and overdue workflow deadlines.

## Phase 11: Production persistence and deployment

- [ ] Align the production database schema with the final workflow entities,
  indexes, foreign keys, enum values, and removed legacy tables.
- [ ] Verify the chosen Hibernate production mode against that schema.
- [ ] Decide how existing legacy Job data is archived or discarded.
- [ ] Test deployment against an existing database, not only a newly created test
  schema.
- [ ] Document rollback and compatibility expectations.

No database migration scripts should be added unless that project decision is
explicitly changed.

## Recommended implementation order

1. Freeze the production schema, then add the remaining runtime constraints
   and indexes.
2. Resolve manual-run, Distributed Map, Map item context, and cancellation
   policy decisions.
3. Build the Task resource registry and stable error model.
4. Add workflow observability and administrative operations.
5. Align and validate the production database and deployment process.

## Completion definition

The workflow runtime is functionally complete when:

- all eight ASL state types execute durably;
- valid supported definitions survive process restarts;
- scheduled and manual runs share the same execution path;
- retries, catches, waits, timeouts, cancellation, Parallel, and Map are
  idempotent;
- unsupported ASL features are rejected during activation;
- database state alone is sufficient to reconstruct and resume execution.
