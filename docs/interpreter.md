# Workflow Interpreter Internals

The `WorkflowInterpreter` is the core transition engine for the ASL Workflow scheduler. It is responsible for moving a single branch of execution (an `ExecutionScope`) forward by evaluating JSONata expressions, delegating to `StateExecutor` instances, and durably persisting outcomes in PostgreSQL.

## The `advance` Loop

The primary entry point is `WorkflowInterpreter.advance(UUID executionScopeId)`. This method is transactional and performs pessimistic row-level locking on the `ExecutionScope` to guarantee safe concurrent settlement (especially critical for joining `Parallel` and `Map` states).

```java
@Transactional
public InterpreterOutcome advance(UUID executionScopeId) {
    ExecutionScope scope = executionScopeRepository
            .findByIdForUpdate(executionScopeId)
            .orElseThrow(() -> new EntityNotFoundException("Execution scope does not exist"));

    // 1. Check for terminal states or already waiting/dispatched work
    if (scope.getStatus() == ExecutionScopeStatus.SUCCEEDED) { ... }
    
    // 2. Load the ASL state definition
    String stateName = scope.getCurrentStateName();
    JsonNode stateDefinition = definitionNavigator.state(scope, stateName);
    AslStateType stateType = readStateType(stateDefinition);

    // 3. Delegate to parallel/map logic or fetch specific executor
    if (stateType == AslStateType.PARALLEL) {
        return advanceParallel(scope, workflowExecution, stateName, stateDefinition);
    }
    // ...

    StateExecutor executor = executors.get(stateType);

    // 4. Create the state context and execute
    StateExecution stateExecution = createStateExecution(scope, stateName, stateType, now);
    StateExecutionContext context = new StateExecutionContext(
            readJson(scope.getCurrentStateInput()),
            readJson(scope.getVariables()),
            contextObject(workflowExecution, scope, stateExecution)
    );
    StateOutcome outcome = executor.execute(stateDefinition, context);

    // 5. Apply the outcome
    return applyOutcome(scope, workflowExecution, stateExecution, outcome);
}
```

### Locking Strategy

`findByIdForUpdate(executionScopeId)` translates to a `SELECT ... FOR UPDATE` query in PostgreSQL. When multiple worker threads or background schedulers attempt to advance the exact same scope simultaneously (e.g., a `Parallel` branch that just finished and wants to resume its parent), the lock forces them to serialize.

## Pluggable State Executors

The interpreter delegates the actual work of a state to implementations of the `StateExecutor` interface. These are dynamically loaded into an `EnumMap<AslStateType, StateExecutor>` during interpreter construction.

Each executor returns a `StateOutcome` record indicating what the engine should do next:
- `StateOutcome.Continue`: Move to the next state.
- `StateOutcome.Waiting`: Suspend execution until a specific time (used by `Wait`).
- `StateOutcome.DispatchTask`: Suspend execution and queue a worker task (used by `Task`).
- `StateOutcome.Fail`: Fail the scope (used by `Fail`).
- `StateOutcome.Succeed`: Terminate the scope successfully (used by `Succeed`).

## Evaluating Expressions with JSONata

AWS Step Functions traditionally uses JSONPath, but this engine replaces it with **JSONata** for significantly richer mapping and transformations. 

The `AslJsonataEvaluator` intercepts any string starting with `{%` and ending with `%}`. 

```java
public JsonNode evaluate(JsonNode value, StateExecutionContext context) {
    if (value.isString() && isExpression(value.stringValue())) {
        return evaluateExpression(value.stringValue(), context);
    }
    // Recursively walk objects and arrays
    // ...
}
```

Inside `evaluateExpression()`, the evaluator binds several implicit variables to the JSONata `Environment`:
- `$states.input`: The input provided to the current state.
- `$states.result`: The result returned by a Task (available during `Assign` or `Output`).
- `$states.errorOutput`: Error details (available during `Catch`).
- `$states.context`: Metadata about the workflow execution.
- `$varName`: User-defined variables declared via the `Assign` field.

This execution is strictly bounded. `parsed.evaluateSynced(...)` accepts `evaluationTimeoutMs` and `maximumDepth` arguments to prevent malicious or poorly written JSONata scripts (like infinite recursive loops) from freezing the interpreter threads.

## Outcome Application

Once a `StateExecutor` yields a `StateOutcome`, the `applyOutcome` method translates it into database state.

For example, if a Task is dispatched:
```java
if (outcome instanceof StateOutcome.DispatchTask task) {
    stateExecution.setStatus(StateExecutionStatus.PENDING);
    // Create an execution attempt for workers to pick up
    StateExecutionAttempt attempt = new StateExecutionAttempt();
    attempt.setStatus(StateExecutionAttemptStatus.PENDING);
    attempt.setArguments(writeTaskArguments(task.arguments()));
    attemptRepository.save(attempt);

    // Suspend the scope
    scope.setStatus(ExecutionScopeStatus.WAITING);
    return new InterpreterOutcome.Dispatched(attempt.getId());
}
```

If a state fails naturally (e.g., an unhandled Task error), `applyOutcome` invokes the `AslRetryResolver` to check if a `Retry` array applies. If so, it schedules a future task attempt. If not, it invokes the `AslCatchResolver` to transition the scope to an error-handling state block.

## Resume Architecture

The `InterpreterOutcome` returned by `advance()` tells the `WorkflowExecutionRunner.drive()` loop whether it can keep executing inline.
- If it returns `Continued` or `Succeeded`, the scope stays in the `Deque<UUID>` frontier and is polled again immediately.
- If it returns `Waiting` or `Dispatched`, it is removed from the frontier. It will only re-enter the runnable queue when a worker or a background watchdog calls `interpreter.completeTaskSuccess()` or `resume()`.
