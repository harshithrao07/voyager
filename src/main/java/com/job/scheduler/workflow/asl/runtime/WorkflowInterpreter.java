package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.AslStateType;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.enums.StateExecutionStatus;
import com.job.scheduler.enums.StateExecutionAttemptKind;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowInterpreter {
    private final ExecutionScopeRepository executionScopeRepository;
    private final StateExecutionRepository stateExecutionRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final StateExecutionAttemptRepository attemptRepository;
    private final AslDefinitionNavigator definitionNavigator;
    private final ObjectMapper objectMapper;
    private final AslJsonataEvaluator jsonataEvaluator;
    private final AslVariableAssignmentEvaluator assignmentEvaluator;
    private final AslRetryResolver retryResolver;
    private final AslCatchResolver catchResolver;
    private final ExecutionScopeCoordinator scopeCoordinator;
    private final WorkflowPayloadLimits payloadLimits;
    private final int mapMaxConcurrency;
    private final long mapPollDelayMillis;
    private final Map<AslStateType, StateExecutor> executors;

    public WorkflowInterpreter(
            ExecutionScopeRepository executionScopeRepository,
            StateExecutionRepository stateExecutionRepository,
            WorkflowExecutionRepository workflowExecutionRepository,
            StateExecutionAttemptRepository attemptRepository,
            AslDefinitionNavigator definitionNavigator,
            ObjectMapper objectMapper,
            AslJsonataEvaluator jsonataEvaluator,
            AslVariableAssignmentEvaluator assignmentEvaluator,
            AslRetryResolver retryResolver,
            AslCatchResolver catchResolver,
            ExecutionScopeCoordinator scopeCoordinator,
            WorkflowPayloadLimits payloadLimits,
            @org.springframework.beans.factory.annotation.Value(
                    "${scheduler.workflow.map-max-concurrency:64}")
            int mapMaxConcurrency,
            @org.springframework.beans.factory.annotation.Value(
                    "${scheduler.workflow.map-poll-delay-ms:1000}")
            long mapPollDelayMillis,
            List<StateExecutor> stateExecutors
    ) {
        this.executionScopeRepository = executionScopeRepository;
        this.stateExecutionRepository = stateExecutionRepository;
        this.workflowExecutionRepository = workflowExecutionRepository;
        this.attemptRepository = attemptRepository;
        this.definitionNavigator = definitionNavigator;
        this.objectMapper = objectMapper;
        this.jsonataEvaluator = jsonataEvaluator;
        this.assignmentEvaluator = assignmentEvaluator;
        this.retryResolver = retryResolver;
        this.catchResolver = catchResolver;
        this.scopeCoordinator = scopeCoordinator;
        this.payloadLimits = payloadLimits;
        this.mapMaxConcurrency = mapMaxConcurrency;
        this.mapPollDelayMillis = mapPollDelayMillis;
        this.executors = new EnumMap<>(AslStateType.class);
        stateExecutors.forEach(executor -> {
            StateExecutor duplicate = executors.put(
                    executor.supportedType(),
                    executor
            );
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate executor for " + executor.supportedType()
                );
            }
        });
    }

    @Transactional
    public InterpreterOutcome advance(UUID executionScopeId) {
        ExecutionScope scope = executionScopeRepository
                .findByIdForUpdate(executionScopeId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Execution scope does not exist"
                ));
        if (scope.getStatus() == ExecutionScopeStatus.SUCCEEDED) {
            return new InterpreterOutcome.Succeeded(readJson(scope.getOutput()));
        }
        if (scope.getStatus() == ExecutionScopeStatus.FAILED) {
            return new InterpreterOutcome.Failed(
                    scope.getError(),
                    scope.getCause()
            );
        }
        if (scope.getStatus() == ExecutionScopeStatus.CANCELED) {
            return new InterpreterOutcome.Failed(
                    scope.getError() == null
                            ? "States.Canceled"
                            : scope.getError(),
                    scope.getCause()
            );
        }
        if (scope.getStatus() == ExecutionScopeStatus.TIMED_OUT) {
            return new InterpreterOutcome.Failed(
                    "States.Timeout",
                    scope.getCause()
            );
        }

        WorkflowExecution workflowExecution = scope.getWorkflowExecution();
        if (workflowExecution.getStatus()
                == WorkflowExecutionStatus.TIMED_OUT) {
            return new InterpreterOutcome.Failed(
                    "States.Timeout",
                    workflowExecution.getCause()
            );
        }
        InterpreterOutcome waitingOutcome =
                resumeWaitingStateIfDue(scope, workflowExecution);
        if (waitingOutcome != null) {
            return waitingOutcome;
        }
        InterpreterOutcome dispatchedOutcome =
                existingDispatchedTask(scope);
        if (dispatchedOutcome != null) {
            return dispatchedOutcome;
        }

        String stateName = scope.getCurrentStateName();
        JsonNode stateDefinition = definitionNavigator.state(scope, stateName);
        AslStateType stateType = readStateType(stateDefinition);
        if (stateType == AslStateType.PARALLEL) {
            return advanceParallel(
                    scope,
                    workflowExecution,
                    stateName,
                    stateDefinition
            );
        }
        if (stateType == AslStateType.MAP) {
            return advanceMap(
                    scope,
                    workflowExecution,
                    stateName,
                    stateDefinition
            );
        }
        StateExecutor executor = executors.get(stateType);
        if (executor == null) {
            return failUnsupported(scope, stateName, stateType);
        }

        Instant now = Instant.now();
        startRuntime(scope, workflowExecution, now);
        StateExecution stateExecution = createStateExecution(
                scope,
                stateName,
                stateType,
                now
        );

        try {
            StateExecutionContext context = new StateExecutionContext(
                    readJson(scope.getCurrentStateInput()),
                    readJson(scope.getVariables()),
                    contextObject(workflowExecution, scope, stateExecution)
            );
            StateOutcome outcome = executor.execute(stateDefinition, context);
            return applyOutcome(
                    scope,
                    workflowExecution,
                    stateExecution,
                    outcome
            );
        } catch (RuntimeException exception) {
            return failState(
                    scope,
                    workflowExecution,
                    stateExecution,
                    errorFor(exception),
                    exception.getMessage()
            );
        }
    }

    private void startRuntime(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            Instant now
    ) {
        if (workflowExecution.getStartedAt() == null) {
            workflowExecution.setStartedAt(now);
        }
        workflowExecution.setStatus(WorkflowExecutionStatus.RUNNING);
        if (scope.getStartedAt() == null) {
            scope.setStartedAt(now);
        }
        scope.setStatus(ExecutionScopeStatus.RUNNING);
    }

    private StateExecution createStateExecution(
            ExecutionScope scope,
            String stateName,
            AslStateType stateType,
            Instant now
    ) {
        long sequenceNumber = stateExecutionRepository
                .findFirstByExecutionScopeOrderBySequenceNumberDesc(scope)
                .map(previous -> previous.getSequenceNumber() + 1)
                .orElse(1L);

        StateExecution stateExecution = new StateExecution();
        stateExecution.setExecutionScope(scope);
        stateExecution.setSequenceNumber(sequenceNumber);
        stateExecution.setStateName(stateName);
        stateExecution.setStateType(stateType);
        stateExecution.setStatus(StateExecutionStatus.RUNNING);
        stateExecution.setInput(scope.getCurrentStateInput());
        stateExecution.setStartedAt(now);
        return stateExecutionRepository.save(stateExecution);
    }

    private InterpreterOutcome applyOutcome(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution stateExecution,
            StateOutcome outcome
    ) {
        Instant completedAt = Instant.now();
        if (outcome instanceof StateOutcome.Continue continued) {
            String variables = writeVariables(continued.variables());
            String nextInput = writeStateInput(continued.output());
            completeState(stateExecution, continued.output(), completedAt);
            scope.setVariables(variables);
            scope.setCurrentStateName(continued.nextStateName());
            scope.setCurrentStateInput(nextInput);
            scope.setStatus(ExecutionScopeStatus.RUNNING);
            executionScopeRepository.save(scope);
            workflowExecutionRepository.save(workflowExecution);
            return new InterpreterOutcome.Continued(
                    continued.nextStateName(),
                    continued.output()
            );
        }

        if (outcome instanceof StateOutcome.Fail failed) {
            return failState(
                    scope,
                    workflowExecution,
                    stateExecution,
                    failed.error(),
                    failed.cause()
            );
        }

        if (outcome instanceof StateOutcome.Waiting waiting) {
            String output = writeOutput(waiting.output());
            String variables = writeVariables(waiting.variables());
            String nextInput = writeStateInput(waiting.output());
            stateExecution.setStatus(StateExecutionStatus.WAITING);
            stateExecution.setOutput(output);
            stateExecutionRepository.save(stateExecution);
            scope.setVariables(variables);
            scope.setCurrentStateName(waiting.nextStateName());
            scope.setCurrentStateInput(nextInput);
            scope.setStatus(ExecutionScopeStatus.WAITING);
            scope.setWakeAt(waiting.wakeAt());
            setWorkflowStatusIfRoot(
                    scope,
                    workflowExecution,
                    WorkflowExecutionStatus.WAITING
            );
            executionScopeRepository.save(scope);
            workflowExecutionRepository.save(workflowExecution);
            return new InterpreterOutcome.Waiting(waiting.wakeAt());
        }

        if (outcome instanceof StateOutcome.DispatchTask task) {
            stateExecution.setStatus(StateExecutionStatus.PENDING);
            stateExecution.setResource(task.resource());
            stateExecutionRepository.save(stateExecution);

            StateExecutionAttempt attempt = new StateExecutionAttempt();
            attempt.setStateExecution(stateExecution);
            attempt.setAttemptNumber(1);
            attempt.setStatus(StateExecutionAttemptStatus.PENDING);
            attempt.setArguments(writeTaskArguments(task.arguments()));
            attempt.setTimeoutSeconds(task.timeoutSeconds());
            attempt.setHeartbeatSeconds(task.heartbeatSeconds());
            attempt.setAvailableAt(Instant.now());
            attempt = attemptRepository.save(attempt);

            scope.setStatus(ExecutionScopeStatus.WAITING);
            setWorkflowStatusIfRoot(
                    scope,
                    workflowExecution,
                    WorkflowExecutionStatus.QUEUED
            );
            executionScopeRepository.save(scope);
            workflowExecutionRepository.save(workflowExecution);
            return new InterpreterOutcome.Dispatched(attempt.getId());
        }

        StateOutcome.Succeed succeeded = (StateOutcome.Succeed) outcome;
        String variables = writeVariables(succeeded.variables());
        completeState(stateExecution, succeeded.output(), completedAt);
        scope.setVariables(variables);
        return completeScope(
                scope,
                workflowExecution,
                succeeded.output(),
                completedAt
        );
    }

    private InterpreterOutcome existingDispatchedTask(ExecutionScope scope) {
        StateExecution stateExecution = stateExecutionRepository
                .findFirstByExecutionScopeOrderBySequenceNumberDesc(scope)
                .filter(state -> state.getStateType() == AslStateType.TASK)
                .filter(state -> state.getStatus() == StateExecutionStatus.PENDING
                        || state.getStatus() == StateExecutionStatus.RUNNING
                        || state.getStatus() == StateExecutionStatus.RETRY_WAIT)
                .orElse(null);
        if (stateExecution == null) {
            return null;
        }
        return attemptRepository
                .findFirstByStateExecutionOrderByAttemptNumberDesc(stateExecution)
                .map(attempt -> stateExecution.getStatus()
                        == StateExecutionStatus.RETRY_WAIT
                        ? new InterpreterOutcome.RetryScheduled(
                                attempt.getId(),
                                attempt.getAvailableAt()
                        )
                        : new InterpreterOutcome.Dispatched(attempt.getId()))
                .orElse(null);
    }

    @Transactional
    public InterpreterOutcome completeTaskSuccess(
            UUID attemptId,
            JsonNode result
    ) {
        StateExecutionAttempt attempt = attemptRepository
                .findByIdForUpdate(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "State execution attempt does not exist"
                ));
        ExecutionScope guardScope =
                attempt.getStateExecution().getExecutionScope();
        if (isScopeTerminal(guardScope)) {
            return terminalScopeOutcome(attempt, guardScope);
        }
        if (isAttemptTerminal(attempt.getStatus())) {
            return outcomeAfterCompletedAttempt(attempt);
        }
        if (attempt.getStatus() != StateExecutionAttemptStatus.RUNNING) {
            throw new IllegalStateException(
                    "Only RUNNING task attempts can succeed"
            );
        }
        if (attempt.getKind() != StateExecutionAttemptKind.TASK) {
            return completeMapResourceSuccess(attempt, result);
        }

        String resultJson;
        JsonNode variables;
        JsonNode output;
        String variablesJson;
        String outputJson;
        String stateInputJson;
        try {
            resultJson = writeTaskResult(result);
            StateExecution stateExecution = attempt.getStateExecution();
            ExecutionScope scope = stateExecution.getExecutionScope();
            WorkflowExecution workflowExecution = scope.getWorkflowExecution();
            JsonNode stateDefinition = definitionNavigator.state(
                    scope,
                    stateExecution.getStateName()
            );
            StateExecutionContext context = new StateExecutionContext(
                    readJson(stateExecution.getInput()),
                    readJson(scope.getVariables()),
                    contextObject(workflowExecution, scope, stateExecution),
                    result,
                    null
            );
            variables = assignmentEvaluator.apply(
                    stateDefinition.get("Assign"),
                    context
            );
            output = stateDefinition.has("Output")
                    ? jsonataEvaluator.evaluate(
                            stateDefinition.get("Output"),
                            context
                    )
                    : result;
            variablesJson = writeVariables(variables);
            outputJson = writeOutput(output);
            stateInputJson = stateDefinition.path("End").asBoolean(false)
                    ? null
                    : writeStateInput(output);
        } catch (WorkflowPayloadLimitExceededException exception) {
            return failPayloadLimit(attempt, exception);
        }

        Instant completedAt = Instant.now();
        attempt.setStatus(StateExecutionAttemptStatus.SUCCEEDED);
        attempt.setResult(resultJson);
        attempt.setCompletedAt(completedAt);
        attempt.setHeartbeatAt(null);
        if (attempt.getStartedAt() != null) {
            attempt.setDurationMs(
                    java.time.Duration.between(
                            attempt.getStartedAt(),
                            completedAt
                    ).toMillis()
            );
        }
        attemptRepository.save(attempt);

        StateExecution stateExecution = attempt.getStateExecution();
        ExecutionScope scope = stateExecution.getExecutionScope();
        WorkflowExecution workflowExecution = scope.getWorkflowExecution();
        JsonNode stateDefinition = definitionNavigator.state(
                scope,
                stateExecution.getStateName()
        );

        stateExecution.setStatus(StateExecutionStatus.SUCCEEDED);
        stateExecution.setRetryAt(null);
        stateExecution.setError(null);
        stateExecution.setCause(null);
        stateExecution.setOutput(outputJson);
        stateExecution.setCompletedAt(completedAt);
        stateExecutionRepository.save(stateExecution);
        scope.setVariables(variablesJson);

        if (stateDefinition.path("End").asBoolean(false)) {
            return completeScope(scope, workflowExecution, output, completedAt);
        }

        scope.setCurrentStateName(
                stateDefinition.get("Next").stringValue()
        );
        scope.setCurrentStateInput(stateInputJson);
        scope.setStatus(ExecutionScopeStatus.RUNNING);
        workflowExecution.setStatus(WorkflowExecutionStatus.RUNNING);
        executionScopeRepository.save(scope);
        workflowExecutionRepository.save(workflowExecution);
        return new InterpreterOutcome.Continued(
                scope.getCurrentStateName(),
                output
        );
    }

    @Transactional
    public InterpreterOutcome completeTaskFailure(
            UUID attemptId,
            String error,
            String cause
    ) {
        return completeTaskFailure(
                attemptId,
                error,
                cause,
                StateExecutionAttemptStatus.FAILED
        );
    }

    @Transactional
    public InterpreterOutcome completeTaskTimeout(
            UUID attemptId,
            String cause
    ) {
        return completeTaskFailure(
                attemptId,
                "States.Timeout",
                cause,
                StateExecutionAttemptStatus.TIMED_OUT
        );
    }

    private InterpreterOutcome completeTaskFailure(
            UUID attemptId,
            String error,
            String cause,
            StateExecutionAttemptStatus terminalStatus
    ) {
        StateExecutionAttempt attempt = attemptRepository
                .findByIdForUpdate(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "State execution attempt does not exist"
                ));
        ExecutionScope guardScope =
                attempt.getStateExecution().getExecutionScope();
        if (isScopeTerminal(guardScope)) {
            return terminalScopeOutcome(attempt, guardScope);
        }
        if (isAttemptTerminal(attempt.getStatus())) {
            return outcomeAfterCompletedAttempt(attempt);
        }
        if (attempt.getStatus() != StateExecutionAttemptStatus.RUNNING) {
            throw new IllegalStateException(
                    "Only RUNNING task attempts can fail"
            );
        }
        try {
            payloadLimits.validate(
                    error,
                    WorkflowPayloadLimits.Kind.ERROR_DETAILS
            );
            payloadLimits.validate(
                    cause,
                    WorkflowPayloadLimits.Kind.ERROR_DETAILS
            );
        } catch (WorkflowPayloadLimitExceededException exception) {
            return failPayloadLimit(attempt, exception);
        }
        if (attempt.getKind() != StateExecutionAttemptKind.TASK) {
            return completeMapResourceFailure(
                    attempt, terminalStatus, error, cause);
        }
        Instant completedAt = Instant.now();
        attempt.setStatus(terminalStatus);
        attempt.setError(error);
        attempt.setCause(cause);
        attempt.setCompletedAt(completedAt);
        attempt.setHeartbeatAt(null);
        if (attempt.getStartedAt() != null) {
            attempt.setDurationMs(Duration.between(
                    attempt.getStartedAt(),
                    completedAt
            ).toMillis());
        }
        attemptRepository.save(attempt);

        StateExecution stateExecution = attempt.getStateExecution();
        ExecutionScope scope = stateExecution.getExecutionScope();
        WorkflowExecution workflowExecution = scope.getWorkflowExecution();
        JsonNode stateDefinition = definitionNavigator.state(
                scope,
                stateExecution.getStateName()
        );
        List<StateExecutionAttempt> attempts = attemptRepository
                .findByStateExecutionOrderByAttemptNumberAsc(stateExecution);
        AslRetryResolver.RetryDecision retry = retryResolver.resolve(
                stateDefinition.get("Retry"),
                error,
                attempts
        );
        if (retry != null) {
            return scheduleRetry(
                    attempt,
                    stateExecution,
                    scope,
                    workflowExecution,
                    retry,
                    completedAt
            );
        }

        JsonNode catcher = catchResolver.resolve(
                stateDefinition.get("Catch"),
                error
        );
        if (catcher != null) {
            try {
                return applyCatch(
                        stateExecution,
                        scope,
                        workflowExecution,
                        catcher,
                        error,
                        cause,
                        completedAt
                );
            } catch (RuntimeException exception) {
                return failState(
                        scope,
                        workflowExecution,
                        stateExecution,
                        errorFor(exception),
                        exception.getMessage()
                );
            }
        }

        return failState(
                scope,
                workflowExecution,
                stateExecution,
                error,
                cause
        );
    }

    private InterpreterOutcome scheduleRetry(
            StateExecutionAttempt failedAttempt,
            StateExecution stateExecution,
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            AslRetryResolver.RetryDecision retry,
            Instant failedAt
    ) {
        Instant availableAt = failedAt.plus(retry.delay());
        StateExecutionAttempt nextAttempt = new StateExecutionAttempt();
        nextAttempt.setStateExecution(stateExecution);
        nextAttempt.setAttemptNumber(failedAttempt.getAttemptNumber() + 1);
        nextAttempt.setStatus(StateExecutionAttemptStatus.PENDING);
        nextAttempt.setArguments(failedAttempt.getArguments());
        nextAttempt.setTimeoutSeconds(failedAttempt.getTimeoutSeconds());
        nextAttempt.setHeartbeatSeconds(failedAttempt.getHeartbeatSeconds());
        nextAttempt.setAvailableAt(availableAt);
        nextAttempt = attemptRepository.save(nextAttempt);

        stateExecution.setStatus(StateExecutionStatus.RETRY_WAIT);
        stateExecution.setRetryAt(availableAt);
        stateExecution.setError(failedAttempt.getError());
        stateExecution.setCause(failedAttempt.getCause());
        stateExecutionRepository.save(stateExecution);
        scope.setStatus(ExecutionScopeStatus.RETRY_WAIT);
        setWorkflowStatusIfRoot(
                scope,
                workflowExecution,
                WorkflowExecutionStatus.WAITING
        );
        executionScopeRepository.save(scope);
        workflowExecutionRepository.save(workflowExecution);
        return new InterpreterOutcome.RetryScheduled(
                nextAttempt.getId(),
                availableAt
        );
    }

    private InterpreterOutcome applyCatch(
            StateExecution stateExecution,
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            JsonNode catcher,
            String error,
            String cause,
            Instant completedAt
    ) {
        ObjectNode errorOutput = objectMapper.createObjectNode();
        errorOutput.put("Error", error);
        if (cause != null) {
            errorOutput.put("Cause", cause);
        }
        StateExecutionContext context = new StateExecutionContext(
                readJson(stateExecution.getInput()),
                readJson(scope.getVariables()),
                contextObject(workflowExecution, scope, stateExecution),
                null,
                errorOutput
        );
        JsonNode variables = assignmentEvaluator.apply(
                catcher.get("Assign"),
                context
        );
        JsonNode output = catcher.has("Output")
                ? jsonataEvaluator.evaluate(catcher.get("Output"), context)
                : errorOutput;

        stateExecution.setStatus(StateExecutionStatus.SUCCEEDED);
        stateExecution.setRetryAt(null);
        stateExecution.setOutput(writeOutput(output));
        stateExecution.setError(error);
        stateExecution.setCause(cause);
        stateExecution.setCompletedAt(completedAt);
        stateExecutionRepository.save(stateExecution);
        scope.setVariables(writeVariables(variables));
        scope.setCurrentStateName(catcher.get("Next").stringValue());
        scope.setCurrentStateInput(writeStateInput(output));
        scope.setStatus(ExecutionScopeStatus.RUNNING);
        workflowExecution.setStatus(WorkflowExecutionStatus.RUNNING);
        executionScopeRepository.save(scope);
        workflowExecutionRepository.save(workflowExecution);
        return new InterpreterOutcome.Continued(
                scope.getCurrentStateName(),
                output
        );
    }

    private InterpreterOutcome continueAfterCompletedTask(
            StateExecution stateExecution
    ) {
        ExecutionScope scope = stateExecution.getExecutionScope();
        if (scope.getStatus() == ExecutionScopeStatus.SUCCEEDED) {
            return new InterpreterOutcome.Succeeded(
                    readJson(scope.getOutput())
            );
        }
        return new InterpreterOutcome.Continued(
                scope.getCurrentStateName(),
                readJson(scope.getCurrentStateInput())
        );
    }

    /**
     * Reconstructs the durable interpreter outcome after an attempt completion
     * has already committed. Duplicate worker results must never reapply
     * Assign/Output, create another retry, or move the cursor twice.
     */
    private InterpreterOutcome outcomeAfterCompletedAttempt(
            StateExecutionAttempt attempt
    ) {
        StateExecution stateExecution = attempt.getStateExecution();
        ExecutionScope scope = stateExecution.getExecutionScope();
        if (isScopeTerminal(scope)) {
            return terminalScopeOutcome(attempt, scope);
        }

        if (stateExecution.getStatus() == StateExecutionStatus.RETRY_WAIT) {
            StateExecutionAttempt retryAttempt = attemptRepository
                    .findFirstByStateExecutionOrderByAttemptNumberDesc(
                            stateExecution
                    )
                    .orElse(attempt);
            return new InterpreterOutcome.RetryScheduled(
                    retryAttempt.getId(),
                    retryAttempt.getAvailableAt()
            );
        }

        if (scope.getStatus() == ExecutionScopeStatus.WAITING
                && scope.getWakeAt() != null) {
            return new InterpreterOutcome.Waiting(scope.getWakeAt());
        }

        if (scope.getCurrentStateName() != null) {
            return new InterpreterOutcome.Continued(
                    scope.getCurrentStateName(),
                    readJson(scope.getCurrentStateInput())
            );
        }

        return new InterpreterOutcome.Failed(
                attempt.getError() == null
                        ? "States.TaskFailed"
                        : attempt.getError(),
                attempt.getCause()
        );
    }

    private InterpreterOutcome completeScope(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            JsonNode output,
            Instant completedAt
    ) {
        scope.setCurrentStateName(null);
        scope.setCurrentStateInput(null);
        scope.setOutput(writeOutput(output));
        scope.setStatus(ExecutionScopeStatus.SUCCEEDED);
        scope.setCompletedAt(completedAt);
        executionScopeRepository.save(scope);
        if (isRoot(scope)) {
            workflowExecution.setStatus(WorkflowExecutionStatus.SUCCEEDED);
            workflowExecution.setOutput(writeOutput(output));
            workflowExecution.setCompletedAt(completedAt);
            workflowExecutionRepository.save(workflowExecution);
        }
        return new InterpreterOutcome.Succeeded(output);
    }

    private InterpreterOutcome resumeWaitingStateIfDue(
            ExecutionScope scope,
            WorkflowExecution workflowExecution
    ) {
        StateExecution waitingState = stateExecutionRepository
                .findFirstByExecutionScopeOrderBySequenceNumberDesc(scope)
                .filter(state -> state.getStatus() == StateExecutionStatus.WAITING)
                .orElse(null);
        if (waitingState == null) {
            return null;
        }

        Instant wakeAt = scope.getWakeAt();
        if (wakeAt != null && wakeAt.isAfter(Instant.now())) {
            return new InterpreterOutcome.Waiting(wakeAt);
        }

        Instant completedAt = Instant.now();
        waitingState.setStatus(StateExecutionStatus.SUCCEEDED);
        waitingState.setCompletedAt(completedAt);
        stateExecutionRepository.save(waitingState);
        scope.setWakeAt(null);

        if (scope.getCurrentStateName() == null) {
            JsonNode output = readJson(scope.getCurrentStateInput());
            return completeScope(scope, workflowExecution, output, completedAt);
        }

        scope.setStatus(ExecutionScopeStatus.RUNNING);
        setWorkflowStatusIfRoot(
                scope,
                workflowExecution,
                WorkflowExecutionStatus.RUNNING
        );
        executionScopeRepository.save(scope);
        workflowExecutionRepository.save(workflowExecution);
        return null;
    }

    private void completeState(
            StateExecution stateExecution,
            JsonNode output,
            Instant completedAt
    ) {
        stateExecution.setStatus(StateExecutionStatus.SUCCEEDED);
        stateExecution.setOutput(writeOutput(output));
        stateExecution.setCompletedAt(completedAt);
        stateExecutionRepository.save(stateExecution);
    }

    private InterpreterOutcome failUnsupported(
            ExecutionScope scope,
            String stateName,
            AslStateType stateType
    ) {
        return failScope(
                scope,
                scope.getWorkflowExecution(),
                "States.RuntimeUnsupported",
                "No runtime executor exists for "
                        + stateType
                        + " state "
                        + stateName
        );
    }

    private InterpreterOutcome failState(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution stateExecution,
            String error,
            String cause
    ) {
        FailureDetails failure = limitedFailure(error, cause);
        Instant completedAt = Instant.now();
        stateExecution.setStatus(StateExecutionStatus.FAILED);
        stateExecution.setError(failure.error());
        stateExecution.setCause(failure.cause());
        stateExecution.setCompletedAt(completedAt);
        stateExecutionRepository.save(stateExecution);
        return failScope(
                scope,
                workflowExecution,
                failure.error(),
                failure.cause()
        );
    }

    private InterpreterOutcome failScope(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            String error,
            String cause
    ) {
        FailureDetails failure = limitedFailure(error, cause);
        Instant completedAt = Instant.now();
        scope.setStatus(ExecutionScopeStatus.FAILED);
        scope.setError(failure.error());
        scope.setCause(failure.cause());
        scope.setCompletedAt(completedAt);
        executionScopeRepository.save(scope);
        if (isRoot(scope)) {
            workflowExecution.setStatus(WorkflowExecutionStatus.FAILED);
            workflowExecution.setError(failure.error());
            workflowExecution.setCause(failure.cause());
            workflowExecution.setCompletedAt(completedAt);
            workflowExecutionRepository.save(workflowExecution);
        }
        return new InterpreterOutcome.Failed(
                failure.error(),
                failure.cause()
        );
    }

    private boolean isRoot(ExecutionScope scope) {
        return scope.getScopeType() == ExecutionScopeType.ROOT;
    }

    /**
     * Workflow-execution status mirrors the root scope only. A child scope
     * (Parallel branch or Map iteration) settling, waiting, or dispatching must
     * not move the whole execution, which may still have other live branches.
     */
    private void setWorkflowStatusIfRoot(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            WorkflowExecutionStatus status
    ) {
        if (isRoot(scope)) {
            workflowExecution.setStatus(status);
        }
    }

    private InterpreterOutcome advanceParallel(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            String stateName,
            JsonNode stateDefinition
    ) {
        StateExecution latest = stateExecutionRepository
                .findFirstByExecutionScopeAndStateNameOrderBySequenceNumberDesc(
                        scope,
                        stateName
                )
                .orElse(null);
        StateExecutionStatus status =
                latest == null ? null : latest.getStatus();

        if (latest == null
                || status == StateExecutionStatus.SUCCEEDED
                || status == StateExecutionStatus.FAILED) {
            return forkParallel(
                    scope,
                    workflowExecution,
                    stateName,
                    stateDefinition
            );
        }
        if (status == StateExecutionStatus.RUNNING) {
            return joinParallel(
                    scope,
                    workflowExecution,
                    stateName,
                    stateDefinition,
                    latest
            );
        }

        // RETRY_WAIT: re-fork once the backoff has elapsed, else keep waiting.
        Instant wakeAt = scope.getWakeAt();
        if (wakeAt != null && wakeAt.isAfter(Instant.now())) {
            return new InterpreterOutcome.Waiting(wakeAt);
        }
        latest.setStatus(StateExecutionStatus.FAILED);
        latest.setCompletedAt(Instant.now());
        stateExecutionRepository.save(latest);
        return forkParallel(scope, workflowExecution, stateName, stateDefinition);
    }

    private InterpreterOutcome forkParallel(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            String stateName,
            JsonNode stateDefinition
    ) {
        Instant now = Instant.now();
        startRuntime(scope, workflowExecution, now);
        StateExecution forkExecution = createStateExecution(
                scope,
                stateName,
                AslStateType.PARALLEL,
                now
        );

        JsonNode branches = stateDefinition.get("Branches");
        if (branches == null || !branches.isArray() || branches.isEmpty()) {
            return failState(
                    scope,
                    workflowExecution,
                    forkExecution,
                    "States.Runtime",
                    "Parallel state " + stateName + " has no Branches"
            );
        }

        List<UUID> childScopeIds = new ArrayList<>();
        try {
            JsonNode input = readJson(scope.getCurrentStateInput());
            StateExecutionContext context = new StateExecutionContext(
                    input,
                    readJson(scope.getVariables()),
                    contextObject(workflowExecution, scope, forkExecution)
            );
            JsonNode branchInput = stateDefinition.has("Arguments")
                    ? jsonataEvaluator.evaluate(
                            stateDefinition.get("Arguments"),
                            context
                    )
                    : input;

            long generation = forkExecution.getSequenceNumber();
            for (int index = 0; index < branches.size(); index++) {
                ExecutionScope branch = scopeCoordinator.forkBranch(
                        scope,
                        stateName,
                        generation,
                        index,
                        branchInput
                );
                childScopeIds.add(branch.getId());
            }
        } catch (RuntimeException exception) {
            return failState(
                    scope,
                    workflowExecution,
                    forkExecution,
                    errorFor(exception),
                    exception.getMessage()
            );
        }

        scope.setStatus(ExecutionScopeStatus.WAITING);
        scope.setWakeAt(null);
        setWorkflowStatusIfRoot(
                scope,
                workflowExecution,
                WorkflowExecutionStatus.RUNNING
        );
        executionScopeRepository.save(scope);
        workflowExecutionRepository.save(workflowExecution);
        return new InterpreterOutcome.Forked(scope.getId(), childScopeIds);
    }

    private InterpreterOutcome joinParallel(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            String stateName,
            JsonNode stateDefinition,
            StateExecution forkExecution
    ) {
        long generation = forkExecution.getSequenceNumber();
        List<ExecutionScope> children = scopeCoordinator.generationChildren(
                scope,
                stateName,
                generation
        );
        ExecutionScopeCoordinator.ChildSettlement settlement =
                scopeCoordinator.settle(children);
        if (!settlement.allSettled()) {
            scope.setStatus(ExecutionScopeStatus.WAITING);
            scope.setWakeAt(null);
            executionScopeRepository.save(scope);
            return new InterpreterOutcome.Joining(scope.getId());
        }

        Instant now = Instant.now();
        if (settlement.anyFailed()) {
            return resolveParallelFailure(
                    scope,
                    workflowExecution,
                    stateName,
                    stateDefinition,
                    forkExecution,
                    settlement,
                    now
            );
        }

        try {
            ArrayNode result = objectMapper.createArrayNode();
            for (JsonNode branchOutput : settlement.orderedOutputs()) {
                result.add(branchOutput == null
                        ? objectMapper.nullNode()
                        : branchOutput);
            }
            StateExecutionContext context = new StateExecutionContext(
                    readJson(forkExecution.getInput()),
                    readJson(scope.getVariables()),
                    contextObject(workflowExecution, scope, forkExecution),
                    result,
                    null
            );
            JsonNode variables = assignmentEvaluator.apply(
                    stateDefinition.get("Assign"),
                    context
            );
            JsonNode output = stateDefinition.has("Output")
                    ? jsonataEvaluator.evaluate(
                            stateDefinition.get("Output"),
                            context
                    )
                    : result;

            completeState(forkExecution, output, now);
            scope.setVariables(writeVariables(variables));
            if (stateDefinition.path("End").asBoolean(false)) {
                return completeScope(scope, workflowExecution, output, now);
            }
            scope.setCurrentStateName(
                    stateDefinition.get("Next").stringValue()
            );
            scope.setCurrentStateInput(writeStateInput(output));
            scope.setStatus(ExecutionScopeStatus.RUNNING);
            setWorkflowStatusIfRoot(
                    scope,
                    workflowExecution,
                    WorkflowExecutionStatus.RUNNING
            );
            executionScopeRepository.save(scope);
            workflowExecutionRepository.save(workflowExecution);
            return new InterpreterOutcome.Continued(
                    scope.getCurrentStateName(),
                    output
            );
        } catch (RuntimeException exception) {
            return failState(
                    scope,
                    workflowExecution,
                    forkExecution,
                    errorFor(exception),
                    exception.getMessage()
            );
        }
    }

    private InterpreterOutcome resolveParallelFailure(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            String stateName,
            JsonNode stateDefinition,
            StateExecution forkExecution,
            ExecutionScopeCoordinator.ChildSettlement settlement,
            Instant now
    ) {
        return resolveCompoundFailure(
                scope,
                workflowExecution,
                stateName,
                stateDefinition,
                forkExecution,
                "States.BranchFailed",
                settlement.firstCause(),
                now
        );
    }

    private InterpreterOutcome applyCompoundCatch(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution forkExecution,
            JsonNode catcher,
            String error,
            String cause,
            Instant completedAt
    ) {
        ObjectNode errorOutput = objectMapper.createObjectNode();
        errorOutput.put("Error", error);
        if (cause != null) {
            errorOutput.put("Cause", cause);
        }
        StateExecutionContext context = new StateExecutionContext(
                readJson(forkExecution.getInput()),
                readJson(scope.getVariables()),
                contextObject(workflowExecution, scope, forkExecution),
                null,
                errorOutput
        );
        JsonNode variables = assignmentEvaluator.apply(
                catcher.get("Assign"),
                context
        );
        JsonNode output = catcher.has("Output")
                ? jsonataEvaluator.evaluate(catcher.get("Output"), context)
                : errorOutput;

        forkExecution.setStatus(StateExecutionStatus.SUCCEEDED);
        forkExecution.setError(error);
        forkExecution.setCause(cause);
        forkExecution.setRetryAt(null);
        forkExecution.setOutput(writeOutput(output));
        forkExecution.setCompletedAt(completedAt);
        stateExecutionRepository.save(forkExecution);
        scope.setVariables(writeVariables(variables));
        scope.setCurrentStateName(catcher.get("Next").stringValue());
        scope.setCurrentStateInput(writeStateInput(output));
        scope.setStatus(ExecutionScopeStatus.RUNNING);
        setWorkflowStatusIfRoot(
                scope,
                workflowExecution,
                WorkflowExecutionStatus.RUNNING
        );
        executionScopeRepository.save(scope);
        workflowExecutionRepository.save(workflowExecution);
        return new InterpreterOutcome.Continued(
                scope.getCurrentStateName(),
                output
        );
    }

    private InterpreterOutcome advanceMap(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            String stateName,
            JsonNode stateDefinition
    ) {
        StateExecution latest = stateExecutionRepository
                .findFirstByExecutionScopeAndStateNameOrderBySequenceNumberDesc(
                        scope,
                        stateName
                )
                .orElse(null);
        StateExecutionStatus status =
                latest == null ? null : latest.getStatus();

        if (latest == null
                || status == StateExecutionStatus.SUCCEEDED
                || status == StateExecutionStatus.FAILED) {
            return forkMap(scope, workflowExecution, stateName, stateDefinition);
        }
        if (status == StateExecutionStatus.RUNNING) {
            return progressMap(
                    scope,
                    workflowExecution,
                    stateName,
                    stateDefinition,
                    latest
            );
        }

        // RETRY_WAIT: re-run the whole Map once the backoff has elapsed.
        Instant wakeAt = scope.getWakeAt();
        if (wakeAt != null && wakeAt.isAfter(Instant.now())) {
            return new InterpreterOutcome.Waiting(wakeAt);
        }
        latest.setStatus(StateExecutionStatus.FAILED);
        latest.setCompletedAt(Instant.now());
        stateExecutionRepository.save(latest);
        return forkMap(scope, workflowExecution, stateName, stateDefinition);
    }

    private InterpreterOutcome forkMap(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            String stateName,
            JsonNode stateDefinition
    ) {
        Instant now = Instant.now();
        startRuntime(scope, workflowExecution, now);
        StateExecution forkExecution = createStateExecution(
                scope,
                stateName,
                AslStateType.MAP,
                now
        );
        String unsupported = unsupportedMapFeature(stateDefinition);
        if (unsupported != null) {
            return failState(
                    scope,
                    workflowExecution,
                    forkExecution,
                    "States.Runtime",
                    "Map runtime does not yet implement " + unsupported
            );
        }
        return progressMap(
                scope,
                workflowExecution,
                stateName,
                stateDefinition,
                forkExecution
        );
    }

    /**
     * Forks the next window of Map iterations (respecting MaxConcurrency), or
     * joins when every item has been forked and settled. Re-entered on each
     * iteration settlement and by the wait-scheduler backstop, re-deriving the
     * item list and live iteration scopes each time, so it is idempotent.
     */
    private InterpreterOutcome progressMap(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            String stateName,
            JsonNode stateDefinition,
            StateExecution forkExecution
    ) {
        long generation = forkExecution.getSequenceNumber();
        Instant now = Instant.now();
        boolean batched = stateDefinition.has("ItemBatcher");

        // READ phase: fetch items from the ItemReader resource before forking.
        if (stateDefinition.has("ItemReader")) {
            StateExecutionAttempt readerAttempt = attemptRepository
                    .findFirstByStateExecutionAndKindOrderByAttemptNumberDesc(
                            forkExecution, StateExecutionAttemptKind.READER)
                    .orElse(null);
            if (readerAttempt == null) {
                return dispatchMapResource(
                        scope, workflowExecution, forkExecution,
                        stateDefinition.get("ItemReader"),
                        StateExecutionAttemptKind.READER, null);
            }
            if (readerAttempt.getStatus()
                    != StateExecutionAttemptStatus.SUCCEEDED) {
                // Reader still in flight; failures are turned into
                // States.ItemReaderFailed by completeTaskFailure.
                return new InterpreterOutcome.Dispatched(readerAttempt.getId());
            }
            // Reader finished: resolveMapItems reads its result below.
        }

        List<JsonNode> rawItems;
        List<JsonNode> batchedInputs;
        try {
            rawItems = resolveMapItems(
                    scope, workflowExecution, forkExecution, stateDefinition);
            batchedInputs = batched
                    ? buildBatches(scope, workflowExecution, forkExecution,
                            stateDefinition, rawItems)
                    : null;
        } catch (RuntimeException exception) {
            return failState(
                    scope,
                    workflowExecution,
                    forkExecution,
                    errorFor(exception),
                    exception.getMessage()
            );
        }
        int iterationCount = batched ? batchedInputs.size() : rawItems.size();

        List<ExecutionScope> existing = scopeCoordinator.generationChildren(
                scope,
                stateName,
                generation
        );
        ExecutionScopeCoordinator.ChildSettlement settlement =
                scopeCoordinator.settle(existing);
        int failed = countByStatus(existing, ExecutionScopeStatus.FAILED)
                + countByStatus(existing, ExecutionScopeStatus.TIMED_OUT);

        boolean toleranceConfigured =
                stateDefinition.has("ToleratedFailureCount")
                        || stateDefinition.has("ToleratedFailurePercentage");
        if (toleranceConfigured) {
            if (mapFailuresExceedThreshold(scope, workflowExecution,
                    forkExecution, stateDefinition, failed, iterationCount)) {
                scopeCoordinator.cancelGeneration(scope, stateName, generation);
                return resolveCompoundFailure(
                        scope,
                        workflowExecution,
                        stateName,
                        stateDefinition,
                        forkExecution,
                        "States.ExceedToleratedFailureThreshold",
                        "Map exceeded the tolerated failure threshold: "
                                + failed + " of " + iterationCount
                                + " iterations failed",
                        now
                );
            }
        } else if (settlement.anyFailed()) {
            scopeCoordinator.cancelGeneration(scope, stateName, generation);
            return resolveCompoundFailure(
                    scope,
                    workflowExecution,
                    stateName,
                    stateDefinition,
                    forkExecution,
                    settlement.firstError() != null
                            ? settlement.firstError()
                            : "States.TaskFailed",
                    settlement.firstCause(),
                    now
            );
        }

        if (existing.size() == iterationCount && settlement.allSettled()) {
            return finishMap(
                    scope,
                    workflowExecution,
                    stateName,
                    stateDefinition,
                    forkExecution,
                    settlement,
                    now
            );
        }

        int active = countNonTerminal(existing);
        int limit = effectiveMapConcurrency(
                scope,
                workflowExecution,
                forkExecution,
                stateDefinition,
                iterationCount
        );
        int slots = Math.max(0, limit - active);
        int nextIndex = existing.size();
        List<UUID> newChildScopeIds = new ArrayList<>();
        try {
            for (int index = nextIndex;
                    index < Math.min(iterationCount, nextIndex + slots);
                    index++) {
                JsonNode iterationInput = batched
                        ? batchedInputs.get(index)
                        : applyItemSelector(
                                scope,
                                workflowExecution,
                                forkExecution,
                                stateDefinition,
                                rawItems.get(index),
                                index
                        );
                // The raw array element backs $$.Map.Item.Value inside the
                // iteration. Batched iterations process a synthesized batch, so
                // a single per-iteration item value is not defined (null).
                ExecutionScope iteration = scopeCoordinator.forkIteration(
                        scope,
                        stateName,
                        generation,
                        index,
                        iterationInput,
                        batched ? null : rawItems.get(index)
                );
                newChildScopeIds.add(iteration.getId());
            }
        } catch (RuntimeException exception) {
            return failState(
                    scope,
                    workflowExecution,
                    forkExecution,
                    errorFor(exception),
                    exception.getMessage()
            );
        }

        scope.setStatus(ExecutionScopeStatus.WAITING);
        // Backstop: re-drive via the wait scheduler in case an iteration's
        // settlement signal is lost to a concurrent parent advance, and to
        // recover after a restart.
        scope.setWakeAt(Instant.now().plusMillis(mapPollDelayMillis));
        setWorkflowStatusIfRoot(
                scope,
                workflowExecution,
                WorkflowExecutionStatus.RUNNING
        );
        executionScopeRepository.save(scope);
        workflowExecutionRepository.save(workflowExecution);
        if (newChildScopeIds.isEmpty()) {
            return new InterpreterOutcome.Joining(scope.getId());
        }
        return new InterpreterOutcome.Forked(scope.getId(), newChildScopeIds);
    }

    /**
     * All iterations settled. Collects their outputs in item order, then either
     * runs the WRITE phase (dispatch ResultWriter with {@code $states.result},
     * use its return value as the persisted result description) or finalizes
     * directly with the collected array.
     */
    private InterpreterOutcome finishMap(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            String stateName,
            JsonNode stateDefinition,
            StateExecution forkExecution,
            ExecutionScopeCoordinator.ChildSettlement settlement,
            Instant now
    ) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode itemOutput : settlement.orderedOutputs()) {
            result.add(itemOutput == null
                    ? objectMapper.nullNode()
                    : itemOutput);
        }

        if (stateDefinition.has("ResultWriter")) {
            StateExecutionAttempt writerAttempt = attemptRepository
                    .findFirstByStateExecutionAndKindOrderByAttemptNumberDesc(
                            forkExecution, StateExecutionAttemptKind.WRITER)
                    .orElse(null);
            if (writerAttempt == null) {
                return dispatchMapResource(
                        scope, workflowExecution, forkExecution,
                        stateDefinition.get("ResultWriter"),
                        StateExecutionAttemptKind.WRITER, result);
            }
            if (writerAttempt.getStatus()
                    != StateExecutionAttemptStatus.SUCCEEDED) {
                return new InterpreterOutcome.Dispatched(writerAttempt.getId());
            }
            JsonNode description = readJson(writerAttempt.getResult());
            return finalizeMap(
                    scope, workflowExecution, stateDefinition,
                    forkExecution, description, now);
        }

        return finalizeMap(
                scope, workflowExecution, stateDefinition,
                forkExecution, result, now);
    }

    private InterpreterOutcome finalizeMap(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            JsonNode stateDefinition,
            StateExecution forkExecution,
            JsonNode mapResult,
            Instant now
    ) {
        try {
            StateExecutionContext context = new StateExecutionContext(
                    readJson(forkExecution.getInput()),
                    readJson(scope.getVariables()),
                    contextObject(workflowExecution, scope, forkExecution),
                    mapResult,
                    null
            );
            JsonNode variables = assignmentEvaluator.apply(
                    stateDefinition.get("Assign"),
                    context
            );
            JsonNode output = stateDefinition.has("Output")
                    ? jsonataEvaluator.evaluate(
                            stateDefinition.get("Output"),
                            context
                    )
                    : mapResult;

            completeState(forkExecution, output, now);
            scope.setVariables(writeVariables(variables));
            scope.setWakeAt(null);
            if (stateDefinition.path("End").asBoolean(false)) {
                return completeScope(scope, workflowExecution, output, now);
            }
            scope.setCurrentStateName(
                    stateDefinition.get("Next").stringValue()
            );
            scope.setCurrentStateInput(writeStateInput(output));
            scope.setStatus(ExecutionScopeStatus.RUNNING);
            setWorkflowStatusIfRoot(
                    scope,
                    workflowExecution,
                    WorkflowExecutionStatus.RUNNING
            );
            executionScopeRepository.save(scope);
            workflowExecutionRepository.save(workflowExecution);
            return new InterpreterOutcome.Continued(
                    scope.getCurrentStateName(),
                    output
            );
        } catch (RuntimeException exception) {
            return failState(
                    scope,
                    workflowExecution,
                    forkExecution,
                    errorFor(exception),
                    exception.getMessage()
            );
        }
    }

    /**
     * Single compound error-handling model shared by Parallel and Map. Both
     * compound states retry by re-forking a new generation (no Task attempts),
     * so the retry decision is derived from the error of each prior failed
     * generation rather than from attempt rows. Retry, Catch, and the terminal
     * failure fallthrough are identical for both states; only the synthetic
     * error name differs ({@code States.BranchFailed} for Parallel, a
     * Map-specific error for Map), so callers pass it in.
     */
    private InterpreterOutcome resolveCompoundFailure(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            String stateName,
            JsonNode stateDefinition,
            StateExecution forkExecution,
            String error,
            String cause,
            Instant now
    ) {
        List<String> priorErrors = new ArrayList<>();
        for (StateExecution generation : stateExecutionRepository
                .findByExecutionScopeAndStateNameOrderBySequenceNumberAsc(
                        scope,
                        stateName
                )) {
            if (generation.getError() != null) {
                priorErrors.add(generation.getError());
            }
        }
        priorErrors.add(error);

        AslRetryResolver.RetryDecision retry = retryResolver.resolveFromErrors(
                stateDefinition.get("Retry"),
                error,
                priorErrors
        );
        if (retry != null) {
            Instant availableAt = now.plus(retry.delay());
            forkExecution.setStatus(StateExecutionStatus.RETRY_WAIT);
            forkExecution.setError(error);
            forkExecution.setCause(cause);
            forkExecution.setRetryAt(availableAt);
            stateExecutionRepository.save(forkExecution);
            scope.setStatus(ExecutionScopeStatus.WAITING);
            scope.setWakeAt(availableAt);
            setWorkflowStatusIfRoot(
                    scope,
                    workflowExecution,
                    WorkflowExecutionStatus.WAITING
            );
            executionScopeRepository.save(scope);
            workflowExecutionRepository.save(workflowExecution);
            return new InterpreterOutcome.Waiting(availableAt);
        }

        JsonNode catcher = catchResolver.resolve(
                stateDefinition.get("Catch"),
                error
        );
        if (catcher != null) {
            try {
                return applyCompoundCatch(
                        scope,
                        workflowExecution,
                        forkExecution,
                        catcher,
                        error,
                        cause,
                        now
                );
            } catch (RuntimeException exception) {
                return failState(
                        scope,
                        workflowExecution,
                        forkExecution,
                        errorFor(exception),
                        exception.getMessage()
                );
            }
        }

        return failState(scope, workflowExecution, forkExecution, error, cause);
    }

    /**
     * Dispatches a Map ItemReader or ResultWriter resource as an attempt on the
     * fork StateExecution. It rides the normal dispatch/worker/TaskResourceRouter
     * path; its kind tells the completion methods to re-drive the Map rather than
     * run Task transition logic. For a writer, the collected result array is made
     * available to its Arguments via {@code $states.result}.
     */
    private InterpreterOutcome dispatchMapResource(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution forkExecution,
            JsonNode resourceConfig,
            StateExecutionAttemptKind kind,
            JsonNode resultForContext
    ) {
        JsonNode resourceNode = resourceConfig == null
                ? null : resourceConfig.get("Resource");
        if (resourceNode == null || !resourceNode.isString()) {
            String error = kind == StateExecutionAttemptKind.READER
                    ? "States.ItemReaderFailed"
                    : "States.ResultWriterFailed";
            return failState(scope, workflowExecution, forkExecution, error,
                    "Map resource configuration has no Resource");
        }
        JsonNode arguments;
        try {
            StateExecutionContext context = new StateExecutionContext(
                    readJson(forkExecution.getInput()),
                    readJson(scope.getVariables()),
                    contextObject(workflowExecution, scope, forkExecution),
                    resultForContext,
                    null
            );
            arguments = resourceConfig.has("Arguments")
                    ? jsonataEvaluator.evaluate(
                            resourceConfig.get("Arguments"), context)
                    : objectMapper.createObjectNode();
        } catch (RuntimeException exception) {
            return failState(scope, workflowExecution, forkExecution,
                    errorFor(exception), exception.getMessage());
        }

        int attemptNumber = attemptRepository
                .findFirstByStateExecutionOrderByAttemptNumberDesc(forkExecution)
                .map(attempt -> attempt.getAttemptNumber() + 1)
                .orElse(1);
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setStateExecution(forkExecution);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setKind(kind);
        attempt.setResource(resourceNode.stringValue());
        attempt.setStatus(StateExecutionAttemptStatus.PENDING);
        attempt.setArguments(writeTaskArguments(arguments));
        attempt.setAvailableAt(Instant.now());
        attempt = attemptRepository.save(attempt);

        scope.setStatus(ExecutionScopeStatus.WAITING);
        scope.setWakeAt(null);
        setWorkflowStatusIfRoot(
                scope, workflowExecution, WorkflowExecutionStatus.QUEUED);
        executionScopeRepository.save(scope);
        workflowExecutionRepository.save(workflowExecution);
        return new InterpreterOutcome.Dispatched(attempt.getId());
    }

    private InterpreterOutcome completeMapResourceSuccess(
            StateExecutionAttempt attempt,
            JsonNode result
    ) {
        Instant completedAt = Instant.now();
        attempt.setStatus(StateExecutionAttemptStatus.SUCCEEDED);
        try {
            attempt.setResult(writeTaskResult(result));
        } catch (WorkflowPayloadLimitExceededException exception) {
            return failPayloadLimit(attempt, exception);
        }
        attempt.setCompletedAt(completedAt);
        attempt.setHeartbeatAt(null);
        if (attempt.getStartedAt() != null) {
            attempt.setDurationMs(Duration.between(
                    attempt.getStartedAt(), completedAt).toMillis());
        }
        attemptRepository.save(attempt);
        // Re-drive the Map; its scope still points at the Map state, so the
        // worker's resume re-enters advanceMap with the reader/writer result
        // now available.
        ExecutionScope scope = attempt.getStateExecution().getExecutionScope();
        return new InterpreterOutcome.Continued(
                scope.getCurrentStateName(),
                result == null ? objectMapper.nullNode() : result);
    }

    private InterpreterOutcome completeMapResourceFailure(
            StateExecutionAttempt attempt,
            StateExecutionAttemptStatus terminalStatus,
            String error,
            String cause
    ) {
        Instant completedAt = Instant.now();
        String mappedError = attempt.getKind() == StateExecutionAttemptKind.READER
                ? "States.ItemReaderFailed"
                : "States.ResultWriterFailed";
        String mappedCause = cause != null ? cause : error;
        attempt.setStatus(terminalStatus);
        attempt.setError(mappedError);
        attempt.setCause(mappedCause);
        attempt.setCompletedAt(completedAt);
        attempt.setHeartbeatAt(null);
        if (attempt.getStartedAt() != null) {
            attempt.setDurationMs(Duration.between(
                    attempt.getStartedAt(), completedAt).toMillis());
        }
        attemptRepository.save(attempt);

        StateExecution forkExecution = attempt.getStateExecution();
        ExecutionScope scope = forkExecution.getExecutionScope();
        WorkflowExecution workflowExecution = scope.getWorkflowExecution();
        JsonNode stateDefinition = definitionNavigator.state(
                scope, forkExecution.getStateName());
        return resolveCompoundFailure(scope, workflowExecution,
                forkExecution.getStateName(), stateDefinition, forkExecution,
                mappedError, mappedCause, completedAt);
    }

    private List<JsonNode> resolveMapItems(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution forkExecution,
            JsonNode stateDefinition
    ) {
        JsonNode input = readJson(forkExecution.getInput());
        JsonNode itemsNode;
        if (stateDefinition.has("ItemReader")) {
            itemsNode = readerItems(scope, workflowExecution, forkExecution,
                    stateDefinition.get("ItemReader"));
        } else if (stateDefinition.has("Items")) {
            StateExecutionContext context = new StateExecutionContext(
                    input,
                    readJson(scope.getVariables()),
                    contextObject(workflowExecution, scope, forkExecution)
            );
            itemsNode = jsonataEvaluator.evaluate(
                    stateDefinition.get("Items"),
                    context
            );
        } else {
            itemsNode = input;
        }
        if (itemsNode == null || !itemsNode.isArray()) {
            throw new IllegalStateException(
                    "Map Items did not resolve to an array"
            );
        }
        List<JsonNode> items = new ArrayList<>();
        itemsNode.forEach(items::add);
        return items;
    }

    /**
     * Items produced by the (already completed) ItemReader attempt, capped by
     * {@code ReaderConfig.MaxItems} when present.
     */
    private JsonNode readerItems(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution forkExecution,
            JsonNode itemReader
    ) {
        StateExecutionAttempt readerAttempt = attemptRepository
                .findFirstByStateExecutionAndKindOrderByAttemptNumberDesc(
                        forkExecution, StateExecutionAttemptKind.READER)
                .filter(attempt -> attempt.getStatus()
                        == StateExecutionAttemptStatus.SUCCEEDED)
                .orElseThrow(() -> new IllegalStateException(
                        "Map ItemReader has not produced items"));
        JsonNode itemsNode = readJson(readerAttempt.getResult());
        JsonNode readerConfig = itemReader.get("ReaderConfig");
        if (itemsNode != null && itemsNode.isArray()
                && readerConfig != null && readerConfig.has("MaxItems")) {
            int maxItems = (int) mapNumber(scope, workflowExecution,
                    forkExecution, readerConfig.get("MaxItems"));
            if (maxItems > 0 && itemsNode.size() > maxItems) {
                ArrayNode capped = objectMapper.createArrayNode();
                for (int index = 0; index < maxItems; index++) {
                    capped.add(itemsNode.get(index));
                }
                itemsNode = capped;
            }
        }
        return itemsNode;
    }

    private JsonNode applyItemSelector(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution forkExecution,
            JsonNode stateDefinition,
            JsonNode itemValue,
            int index
    ) {
        if (!stateDefinition.has("ItemSelector")) {
            return itemValue;
        }
        ObjectNode contextNode =
                contextObject(workflowExecution, scope, forkExecution);
        ObjectNode mapItem = objectMapper.createObjectNode();
        mapItem.put("Index", index);
        mapItem.set("Value", itemValue);
        ObjectNode map = objectMapper.createObjectNode();
        map.set("Item", mapItem);
        contextNode.set("Map", map);
        StateExecutionContext context = new StateExecutionContext(
                readJson(forkExecution.getInput()),
                readJson(scope.getVariables()),
                contextNode
        );
        return jsonataEvaluator.evaluate(
                stateDefinition.get("ItemSelector"),
                context
        );
    }

    private int effectiveMapConcurrency(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution forkExecution,
            JsonNode stateDefinition,
            int itemCount
    ) {
        int declared = (int) mapNumber(
                scope,
                workflowExecution,
                forkExecution,
                stateDefinition.get("MaxConcurrency")
        );
        // 0 means unlimited per ASL; cap by the platform safety limit either way.
        int requested = declared <= 0 ? itemCount : declared;
        int capped = Math.min(requested, mapMaxConcurrency);
        return Math.max(1, capped);
    }

    /**
     * Groups Map items into batches per ItemBatcher: each item runs through
     * ItemSelector first, batches close at MaxItemsPerBatch or
     * MaxInputBytesPerBatch, and every batch payload merges BatchInput with the
     * batched items under an {@code Items} array. A single oversized item still
     * forms its own batch.
     */
    private List<JsonNode> buildBatches(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution forkExecution,
            JsonNode stateDefinition,
            List<JsonNode> rawItems
    ) {
        JsonNode batcher = stateDefinition.get("ItemBatcher");
        int maxItemsPerBatch = (int) mapNumber(
                scope, workflowExecution, forkExecution,
                batcher.get("MaxItemsPerBatch"));
        long maxBytesPerBatch = (long) mapNumber(
                scope, workflowExecution, forkExecution,
                batcher.get("MaxInputBytesPerBatch"));
        JsonNode batchInput = null;
        if (batcher.has("BatchInput")) {
            StateExecutionContext context = new StateExecutionContext(
                    readJson(forkExecution.getInput()),
                    readJson(scope.getVariables()),
                    contextObject(workflowExecution, scope, forkExecution)
            );
            batchInput = jsonataEvaluator.evaluate(
                    batcher.get("BatchInput"), context);
        }

        List<JsonNode> batches = new ArrayList<>();
        List<JsonNode> current = new ArrayList<>();
        long currentBytes = 0;
        for (int index = 0; index < rawItems.size(); index++) {
            JsonNode item = applyItemSelector(
                    scope, workflowExecution, forkExecution,
                    stateDefinition, rawItems.get(index), index);
            long itemBytes = serializedBytes(item);
            boolean full = !current.isEmpty() && (
                    (maxItemsPerBatch > 0 && current.size() >= maxItemsPerBatch)
                    || (maxBytesPerBatch > 0
                            && currentBytes + itemBytes > maxBytesPerBatch));
            if (full) {
                batches.add(makeBatch(batchInput, current));
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(item);
            currentBytes += itemBytes;
        }
        if (!current.isEmpty()) {
            batches.add(makeBatch(batchInput, current));
        }
        return batches;
    }

    private JsonNode makeBatch(JsonNode batchInput, List<JsonNode> items) {
        ObjectNode payload = batchInput != null && batchInput.isObject()
                ? (ObjectNode) batchInput.deepCopy()
                : objectMapper.createObjectNode();
        ArrayNode itemsArray = objectMapper.createArrayNode();
        items.forEach(itemsArray::add);
        payload.set("Items", itemsArray);
        return payload;
    }

    private boolean mapFailuresExceedThreshold(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution forkExecution,
            JsonNode stateDefinition,
            int failed,
            int total
    ) {
        if (stateDefinition.has("ToleratedFailureCount")) {
            int countThreshold = (int) mapNumber(
                    scope, workflowExecution, forkExecution,
                    stateDefinition.get("ToleratedFailureCount"));
            if (failed > countThreshold) {
                return true;
            }
        }
        if (stateDefinition.has("ToleratedFailurePercentage")) {
            double percentage = mapNumber(
                    scope, workflowExecution, forkExecution,
                    stateDefinition.get("ToleratedFailurePercentage"));
            if (failed > total * percentage / 100.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates an ASL number that may be a literal or a JSONata expression.
     * Returns 0 when absent or not numeric.
     */
    private double mapNumber(
            ExecutionScope scope,
            WorkflowExecution workflowExecution,
            StateExecution forkExecution,
            JsonNode node
    ) {
        if (node == null) {
            return 0;
        }
        JsonNode value = node;
        if (!node.isNumber()) {
            StateExecutionContext context = new StateExecutionContext(
                    readJson(forkExecution.getInput()),
                    readJson(scope.getVariables()),
                    contextObject(workflowExecution, scope, forkExecution)
            );
            value = jsonataEvaluator.evaluate(node, context);
        }
        return value != null && value.isNumber() ? value.doubleValue() : 0;
    }

    private int countNonTerminal(List<ExecutionScope> scopes) {
        int active = 0;
        for (ExecutionScope scope : scopes) {
            ExecutionScopeStatus status = scope.getStatus();
            if (status != ExecutionScopeStatus.SUCCEEDED
                    && status != ExecutionScopeStatus.FAILED
                    && status != ExecutionScopeStatus.CANCELED
                    && status != ExecutionScopeStatus.TIMED_OUT) {
                active++;
            }
        }
        return active;
    }

    private int countByStatus(
            List<ExecutionScope> scopes,
            ExecutionScopeStatus status
    ) {
        int count = 0;
        for (ExecutionScope scope : scopes) {
            if (scope.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /**
     * Core Map is executable; these advanced features are not implemented yet,
     * so a Map that uses them is rejected at runtime (and blocked at activation
     * by the definition validator). Only ProcessorConfig Mode INLINE (the
     * default) is supported.
     */
    private String unsupportedMapFeature(JsonNode stateDefinition) {
        JsonNode mode = stateDefinition
                .path("ItemProcessor")
                .path("ProcessorConfig")
                .path("Mode");
        if (mode.isString() && !"INLINE".equals(mode.stringValue())) {
            return "ProcessorConfig.Mode=" + mode.stringValue();
        }
        return null;
    }

    private InterpreterOutcome abandonAttempt(
            StateExecutionAttempt attempt,
            ExecutionScope scope
    ) {
        attempt.setStatus(StateExecutionAttemptStatus.CANCELED);
        attempt.setCompletedAt(Instant.now());
        attempt.setHeartbeatAt(null);
        attemptRepository.save(attempt);
        if (scope.getStatus() == ExecutionScopeStatus.SUCCEEDED) {
            return new InterpreterOutcome.Succeeded(readJson(scope.getOutput()));
        }
        return new InterpreterOutcome.Failed(
                scope.getError() == null ? "States.Canceled" : scope.getError(),
                scope.getCause()
        );
    }

    private InterpreterOutcome terminalScopeOutcome(
            StateExecutionAttempt attempt,
            ExecutionScope scope
    ) {
        if (attempt.getStatus() == StateExecutionAttemptStatus.RUNNING
                || attempt.getStatus() == StateExecutionAttemptStatus.QUEUED
                || attempt.getStatus() == StateExecutionAttemptStatus.PENDING) {
            return abandonAttempt(attempt, scope);
        }
        if (scope.getStatus() == ExecutionScopeStatus.SUCCEEDED) {
            return new InterpreterOutcome.Succeeded(readJson(scope.getOutput()));
        }
        return new InterpreterOutcome.Failed(
                scope.getError() == null ? "States.Canceled" : scope.getError(),
                scope.getCause()
        );
    }

    private boolean isScopeTerminal(ExecutionScope scope) {
        return scope.getStatus() == ExecutionScopeStatus.SUCCEEDED
                || scope.getStatus() == ExecutionScopeStatus.FAILED
                || scope.getStatus() == ExecutionScopeStatus.CANCELED
                || scope.getStatus() == ExecutionScopeStatus.TIMED_OUT;
    }

    private boolean isAttemptTerminal(StateExecutionAttemptStatus status) {
        return status == StateExecutionAttemptStatus.SUCCEEDED
                || status == StateExecutionAttemptStatus.FAILED
                || status == StateExecutionAttemptStatus.CANCELED
                || status == StateExecutionAttemptStatus.TIMED_OUT;
    }

    private ObjectNode contextObject(
            WorkflowExecution workflowExecution,
            ExecutionScope scope,
            StateExecution stateExecution
    ) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("WorkflowExecutionId", workflowExecution.getId().toString());
        context.put("ExecutionScopeId", scope.getId().toString());
        context.put("StateExecutionId", stateExecution.getId().toString());
        context.put("StateName", stateExecution.getStateName());
        context.put("StateSequence", stateExecution.getSequenceNumber());
        if (scope.getScopeType() == ExecutionScopeType.MAP_ITERATION
                && scope.getItemIndex() != null) {
            ObjectNode mapItem = objectMapper.createObjectNode();
            mapItem.put("Index", scope.getItemIndex());
            // Value is reconstructed from the durable raw item, so it survives
            // a restart. Absent for batched iterations (no single item value).
            if (scope.getItemValue() != null) {
                mapItem.set("Value", readJson(scope.getItemValue()));
            }
            ObjectNode map = objectMapper.createObjectNode();
            map.set("Item", mapItem);
            context.set("Map", map);
        }
        return context;
    }

    private AslStateType readStateType(JsonNode stateDefinition) {
        String type = stateDefinition.path("Type").stringValue();
        try {
            return AslStateType.valueOf(type.toUpperCase());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unsupported ASL state type: " + type,
                    exception
            );
        }
    }

    private JsonNode readJson(String value) {
        try {
            return value == null
                    ? objectMapper.nullNode()
                    : objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read persisted workflow runtime JSON",
                    exception
            );
        }
    }

    private String writeOutput(JsonNode value) {
        return payloadLimits.serialize(
                value,
                WorkflowPayloadLimits.Kind.OUTPUT
        );
    }

    private String writeStateInput(JsonNode value) {
        String serialized = writeOutput(value);
        payloadLimits.validate(
                serialized,
                WorkflowPayloadLimits.Kind.INPUT
        );
        return serialized;
    }

    private String writeVariables(JsonNode value) {
        return payloadLimits.serialize(
                value,
                WorkflowPayloadLimits.Kind.VARIABLES
        );
    }

    private String writeTaskArguments(JsonNode value) {
        return payloadLimits.serialize(
                value,
                WorkflowPayloadLimits.Kind.TASK_ARGUMENTS
        );
    }

    private String writeTaskResult(JsonNode value) {
        return payloadLimits.serialize(
                value,
                WorkflowPayloadLimits.Kind.TASK_RESULT
        );
    }

    private long serializedBytes(JsonNode value) {
        String serialized = payloadLimits.serialize(
                value,
                WorkflowPayloadLimits.Kind.OUTPUT
        );
        return serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                .length;
    }

    private String errorFor(RuntimeException exception) {
        return exception instanceof WorkflowPayloadLimitExceededException
                ? "States.DataLimitExceeded"
                : "States.QueryEvaluationError";
    }

    private InterpreterOutcome failPayloadLimit(
            StateExecutionAttempt attempt,
            WorkflowPayloadLimitExceededException exception
    ) {
        Instant completedAt = Instant.now();
        attempt.setStatus(StateExecutionAttemptStatus.FAILED);
        attempt.setError("States.DataLimitExceeded");
        attempt.setCause(payloadLimits.fitErrorDetail(exception.getMessage()));
        attempt.setCompletedAt(completedAt);
        attempt.setHeartbeatAt(null);
        if (attempt.getStartedAt() != null) {
            attempt.setDurationMs(Duration.between(
                    attempt.getStartedAt(),
                    completedAt
            ).toMillis());
        }
        attemptRepository.save(attempt);
        StateExecution stateExecution = attempt.getStateExecution();
        ExecutionScope scope = stateExecution.getExecutionScope();
        return failState(
                scope,
                scope.getWorkflowExecution(),
                stateExecution,
                "States.DataLimitExceeded",
                payloadLimits.fitErrorDetail(exception.getMessage())
        );
    }

    private FailureDetails limitedFailure(String error, String cause) {
        if (payloadLimits.exceedsErrorLimit(error)
                || payloadLimits.exceedsErrorLimit(cause)) {
            return new FailureDetails(
                    "States.DataLimitExceeded",
                    payloadLimits.fitErrorDetail(
                            "Error details exceeded the configured UTF-8 byte limit"
                    )
            );
        }
        return new FailureDetails(error, cause);
    }

    private record FailureDetails(String error, String cause) {
    }

}
