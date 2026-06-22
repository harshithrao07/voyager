package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.enums.StateExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import com.job.scheduler.workflow.asl.runtime.AslDefinitionNavigator;
import com.job.scheduler.workflow.asl.runtime.ExecutionScopeCoordinator;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowTimeoutService {
    private static final String TIMEOUT_ERROR = "States.Timeout";
    private static final String ROOT_TIMEOUT_CAUSE =
            "Workflow exceeded its ASL TimeoutSeconds";
    private static final String NESTED_TIMEOUT_CAUSE =
            "Nested machine exceeded its ASL TimeoutSeconds";

    private static final List<WorkflowExecutionStatus> ACTIVE_EXECUTIONS =
            List.of(
                    WorkflowExecutionStatus.PENDING,
                    WorkflowExecutionStatus.QUEUED,
                    WorkflowExecutionStatus.RUNNING,
                    WorkflowExecutionStatus.WAITING
            );
    private static final List<ExecutionScopeStatus> ACTIVE_SCOPES =
            List.of(
                    ExecutionScopeStatus.PENDING,
                    ExecutionScopeStatus.RUNNING,
                    ExecutionScopeStatus.WAITING,
                    ExecutionScopeStatus.RETRY_WAIT
            );

    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final ExecutionScopeRepository executionScopeRepository;
    private final StateExecutionRepository stateExecutionRepository;
    private final StateExecutionAttemptRepository attemptRepository;
    private final AslDefinitionNavigator definitionNavigator;
    private final ExecutionScopeCoordinator scopeCoordinator;

    public List<UUID> overdueRootExecutionIds(Instant now, int limit) {
        return workflowExecutionRepository
                .findByDeadlineAtLessThanEqualAndStatusIn(
                        now,
                        ACTIVE_EXECUTIONS,
                        PageRequest.of(0, limit)
                )
                .stream()
                .map(WorkflowExecution::getId)
                .toList();
    }

    @Transactional
    public boolean timeoutRootExecution(UUID executionId, Instant now) {
        WorkflowExecution execution = workflowExecutionRepository
                .findByIdForUpdate(executionId)
                .orElse(null);
        if (execution == null
                || !ACTIVE_EXECUTIONS.contains(execution.getStatus())
                || execution.getDeadlineAt() == null
                || execution.getDeadlineAt().isAfter(now)) {
            return false;
        }

        execution.setStatus(WorkflowExecutionStatus.TIMED_OUT);
        execution.setError(TIMEOUT_ERROR);
        execution.setCause(ROOT_TIMEOUT_CAUSE);
        execution.setCompletedAt(now);
        workflowExecutionRepository.save(execution);

        for (ExecutionScope scope :
                executionScopeRepository
                        .findByWorkflowExecutionOrderByScopePathAsc(execution)) {
            timeoutScopeRuntime(scope, now, ROOT_TIMEOUT_CAUSE);
        }
        return true;
    }

    public List<UUID> overdueNestedScopeIds(Instant now, int limit) {
        return executionScopeRepository.findNestedTimeoutCandidates(
                        ExecutionScopeType.ROOT,
                        now,
                        ACTIVE_SCOPES
                )
                .stream()
                .filter(scope -> isNestedDeadlineExceeded(scope, now))
                .limit(limit)
                .map(ExecutionScope::getId)
                .toList();
    }

    @Transactional
    public Optional<NestedTimeoutResume> timeoutNestedScope(
            UUID scopeId,
            Instant now
    ) {
        ExecutionScope scope = executionScopeRepository
                .findByIdForUpdate(scopeId)
                .orElse(null);
        if (scope == null
                || scope.getScopeType() == ExecutionScopeType.ROOT
                || !ACTIVE_SCOPES.contains(scope.getStatus())
                || isWorkflowTerminal(scope.getWorkflowExecution().getStatus())
                || !isNestedDeadlineExceeded(scope, now)) {
            return Optional.empty();
        }

        String prefix = scope.getScopePath() + "/";
        timeoutScopeRuntime(scope, now, NESTED_TIMEOUT_CAUSE);
        executionScopeRepository
                .findByWorkflowExecutionAndScopePathStartingWithOrderByScopePathAsc(
                        scope.getWorkflowExecution(),
                        prefix
                )
                .forEach(descendant ->
                        timeoutScopeRuntime(descendant, now, NESTED_TIMEOUT_CAUSE));
        return scopeCoordinator.onChildSettled(scopeId)
                .map(parentScopeId -> new NestedTimeoutResume(
                        scope.getWorkflowExecution().getId(),
                        parentScopeId
                ));
    }

    private boolean isNestedDeadlineExceeded(
            ExecutionScope scope,
            Instant now
    ) {
        if (scope.getStartedAt() == null) {
            return false;
        }
        JsonNode timeout = definitionNavigator
                .readMachine(scope)
                .get("TimeoutSeconds");
        return timeout != null
                && timeout.isIntegralNumber()
                && !scope.getStartedAt()
                        .plusSeconds(timeout.longValue())
                        .isAfter(now);
    }

    private void timeoutScopeRuntime(
            ExecutionScope scope,
            Instant now,
            String cause
    ) {
        if (!isScopeTerminal(scope.getStatus())) {
            scope.setStatus(ExecutionScopeStatus.TIMED_OUT);
            scope.setError(TIMEOUT_ERROR);
            scope.setCause(cause);
            scope.setWakeAt(null);
            scope.setCompletedAt(now);
            executionScopeRepository.save(scope);
        }

        for (StateExecution state :
                stateExecutionRepository
                        .findByExecutionScopeOrderBySequenceNumberAsc(scope)) {
            if (!isStateTerminal(state.getStatus())) {
                state.setStatus(StateExecutionStatus.TIMED_OUT);
                state.setError(TIMEOUT_ERROR);
                state.setCause(cause);
                state.setRetryAt(null);
                state.setCompletedAt(now);
                stateExecutionRepository.save(state);
            }
            for (StateExecutionAttempt attempt :
                    attemptRepository
                            .findByStateExecutionOrderByAttemptNumberAsc(state)) {
                if (!isAttemptTerminal(attempt.getStatus())) {
                    attempt.setStatus(StateExecutionAttemptStatus.TIMED_OUT);
                    attempt.setError(TIMEOUT_ERROR);
                    attempt.setCause(cause);
                    attempt.setHeartbeatAt(null);
                    attempt.setHeartbeatDeadlineAt(null);
                    attempt.setTimeoutAt(null);
                    attempt.setCompletedAt(now);
                    attemptRepository.save(attempt);
                }
            }
        }
    }

    private boolean isWorkflowTerminal(WorkflowExecutionStatus status) {
        return status == WorkflowExecutionStatus.SUCCEEDED
                || status == WorkflowExecutionStatus.FAILED
                || status == WorkflowExecutionStatus.CANCELED
                || status == WorkflowExecutionStatus.TIMED_OUT;
    }

    private boolean isScopeTerminal(ExecutionScopeStatus status) {
        return status == ExecutionScopeStatus.SUCCEEDED
                || status == ExecutionScopeStatus.FAILED
                || status == ExecutionScopeStatus.CANCELED
                || status == ExecutionScopeStatus.TIMED_OUT;
    }

    private boolean isStateTerminal(StateExecutionStatus status) {
        return status == StateExecutionStatus.SUCCEEDED
                || status == StateExecutionStatus.FAILED
                || status == StateExecutionStatus.CANCELED
                || status == StateExecutionStatus.TIMED_OUT;
    }

    private boolean isAttemptTerminal(StateExecutionAttemptStatus status) {
        return status == StateExecutionAttemptStatus.SUCCEEDED
                || status == StateExecutionAttemptStatus.FAILED
                || status == StateExecutionAttemptStatus.CANCELED
                || status == StateExecutionAttemptStatus.TIMED_OUT;
    }

    public record NestedTimeoutResume(
            UUID workflowExecutionId,
            UUID parentScopeId
    ) {
    }
}
