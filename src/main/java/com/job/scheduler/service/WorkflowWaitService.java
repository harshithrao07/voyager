package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowWaitService {
    private final ExecutionScopeRepository executionScopeRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;

    @Transactional
    public void releaseFailedClaim(UUID scopeId, Instant retryAt) {
        ExecutionScope scope = executionScopeRepository.findByIdForUpdate(scopeId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Execution scope does not exist"
                ));
        if (scope.getStatus() == ExecutionScopeStatus.SUCCEEDED
                || scope.getStatus() == ExecutionScopeStatus.FAILED
                || scope.getStatus() == ExecutionScopeStatus.CANCELED
                || scope.getStatus() == ExecutionScopeStatus.TIMED_OUT) {
            return;
        }
        scope.setStatus(ExecutionScopeStatus.WAITING);
        scope.setWakeAt(retryAt);
        scope.getWorkflowExecution().setStatus(WorkflowExecutionStatus.WAITING);
        executionScopeRepository.save(scope);
        workflowExecutionRepository.save(scope.getWorkflowExecution());
    }
}
