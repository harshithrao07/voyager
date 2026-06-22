package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowExecutionCancellationResponseDTO;
import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.enums.StateExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Cancels a workflow execution.
 *
 * <p>Cancellation is <strong>cooperative / best-effort</strong>, by design. It
 * atomically transitions the execution, its scopes, state visits, and any
 * non-terminal attempts to CANCELED, so no further work can be claimed and any
 * late worker result is absorbed idempotently rather than advancing the
 * workflow. It does <strong>not</strong> interrupt a Task handler that is
 * already executing in a worker: that handler runs to completion and its side
 * effect (email sent, webhook delivered, MCP tool invoked) is not rolled back.
 *
 * <p>This contract is intentional. Most Task resources are synchronous external
 * calls; interrupting one mid-flight would leave the external side in an unknown
 * state, which is worse than letting it finish and discarding the result. If a
 * specific resource ever needs interruptible execution, it should be added as an
 * opt-in per handler rather than as a global behavior change here.
 */
@Service
@RequiredArgsConstructor
public class WorkflowExecutionCancellationService {
    private static final String CANCELLATION_ERROR = "Execution.Canceled";
    private static final String CANCELLATION_CAUSE =
            "Workflow execution was canceled by user request";

    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final ExecutionScopeRepository executionScopeRepository;
    private final StateExecutionRepository stateExecutionRepository;
    private final StateExecutionAttemptRepository attemptRepository;

    @Transactional
    public WorkflowExecutionCancellationResponseDTO cancelExecution(
            UUID workflowId,
            UUID executionId
    ) {
        WorkflowExecution execution = workflowExecutionRepository
                .findByIdForUpdate(executionId)
                .filter(candidate ->
                        candidate.getWorkflow().getId().equals(workflowId))
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow execution does not exist"
                ));

        if (execution.getStatus() == WorkflowExecutionStatus.CANCELED
                || isImmutableTerminal(execution.getStatus())) {
            return toResponse(execution);
        }

        Instant canceledAt = Instant.now();
        execution.setStatus(WorkflowExecutionStatus.CANCELED);
        execution.setError(CANCELLATION_ERROR);
        execution.setCause(CANCELLATION_CAUSE);
        execution.setCompletedAt(canceledAt);
        workflowExecutionRepository.save(execution);

        executionScopeRepository
                .findByWorkflowExecutionOrderByScopePathAsc(execution)
                .forEach(scope -> cancelScopeRuntime(scope, canceledAt));

        return toResponse(execution);
    }

    private void cancelScopeRuntime(
            ExecutionScope scope,
            Instant canceledAt
    ) {
        if (!isScopeTerminal(scope.getStatus())) {
            scope.setStatus(ExecutionScopeStatus.CANCELED);
            scope.setError(CANCELLATION_ERROR);
            scope.setCause(CANCELLATION_CAUSE);
            scope.setWakeAt(null);
            scope.setCompletedAt(canceledAt);
            executionScopeRepository.save(scope);
        }

        for (StateExecution state :
                stateExecutionRepository
                        .findByExecutionScopeOrderBySequenceNumberAsc(scope)) {
            if (!isStateTerminal(state.getStatus())) {
                state.setStatus(StateExecutionStatus.CANCELED);
                state.setError(CANCELLATION_ERROR);
                state.setCause(CANCELLATION_CAUSE);
                state.setRetryAt(null);
                state.setCompletedAt(canceledAt);
                stateExecutionRepository.save(state);
            }

            for (StateExecutionAttempt attempt :
                    attemptRepository
                            .findByStateExecutionOrderByAttemptNumberAsc(state)) {
                if (!isAttemptTerminal(attempt.getStatus())) {
                    attempt.setStatus(StateExecutionAttemptStatus.CANCELED);
                    attempt.setError(CANCELLATION_ERROR);
                    attempt.setCause(CANCELLATION_CAUSE);
                    attempt.setHeartbeatAt(null);
                    attempt.setHeartbeatDeadlineAt(null);
                    attempt.setTimeoutAt(null);
                    attempt.setCompletedAt(canceledAt);
                    attemptRepository.save(attempt);
                }
            }
        }
    }

    private boolean isImmutableTerminal(WorkflowExecutionStatus status) {
        return status == WorkflowExecutionStatus.SUCCEEDED
                || status == WorkflowExecutionStatus.FAILED
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

    private WorkflowExecutionCancellationResponseDTO toResponse(
            WorkflowExecution execution
    ) {
        return new WorkflowExecutionCancellationResponseDTO(
                execution.getId(),
                execution.getStatus(),
                execution.getError(),
                execution.getCause(),
                execution.getCompletedAt()
        );
    }
}
