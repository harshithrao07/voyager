package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowWaitServiceTest {
    @Mock
    private ExecutionScopeRepository executionScopeRepository;
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;

    @Test
    void restoresWaitingStateAfterFailedResumeClaim() {
        WorkflowExecution workflowExecution = new WorkflowExecution();
        workflowExecution.setStatus(WorkflowExecutionStatus.RUNNING);
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(workflowExecution);
        scope.setStatus(ExecutionScopeStatus.RUNNING);
        Instant retryAt = Instant.now().plusSeconds(5);
        when(executionScopeRepository.findByIdForUpdate(scope.getId()))
                .thenReturn(Optional.of(scope));
        WorkflowWaitService service = new WorkflowWaitService(
                executionScopeRepository,
                workflowExecutionRepository
        );

        service.releaseFailedClaim(scope.getId(), retryAt);

        assertThat(scope.getStatus()).isEqualTo(ExecutionScopeStatus.WAITING);
        assertThat(scope.getWakeAt()).isEqualTo(retryAt);
        assertThat(workflowExecution.getStatus())
                .isEqualTo(WorkflowExecutionStatus.WAITING);
        verify(executionScopeRepository).save(scope);
        verify(workflowExecutionRepository).save(workflowExecution);
    }
}
