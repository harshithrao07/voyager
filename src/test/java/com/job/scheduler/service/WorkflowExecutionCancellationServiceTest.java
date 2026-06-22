package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.Workflow;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionCancellationServiceTest {
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private ExecutionScopeRepository executionScopeRepository;
    @Mock
    private StateExecutionRepository stateExecutionRepository;
    @Mock
    private StateExecutionAttemptRepository attemptRepository;

    private WorkflowExecutionCancellationService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowExecutionCancellationService(
                workflowExecutionRepository,
                executionScopeRepository,
                stateExecutionRepository,
                attemptRepository
        );
    }

    @Test
    void cancelsExecutionAndAllActiveRuntimeRows() {
        WorkflowExecution execution = execution(WorkflowExecutionStatus.RUNNING);
        ExecutionScope root = new ExecutionScope();
        root.setStatus(ExecutionScopeStatus.WAITING);
        StateExecution state = new StateExecution();
        state.setStatus(StateExecutionStatus.RETRY_WAIT);
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setStatus(StateExecutionAttemptStatus.QUEUED);

        when(workflowExecutionRepository.findByIdForUpdate(execution.getId()))
                .thenReturn(Optional.of(execution));
        when(executionScopeRepository
                .findByWorkflowExecutionOrderByScopePathAsc(execution))
                .thenReturn(List.of(root));
        when(stateExecutionRepository
                .findByExecutionScopeOrderBySequenceNumberAsc(root))
                .thenReturn(List.of(state));
        when(attemptRepository
                .findByStateExecutionOrderByAttemptNumberAsc(state))
                .thenReturn(List.of(attempt));

        var response = service.cancelExecution(
                execution.getWorkflow().getId(),
                execution.getId()
        );

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.CANCELED);
        assertThat(execution.getError()).isEqualTo("Execution.Canceled");
        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.CANCELED);
        assertThat(state.getStatus()).isEqualTo(StateExecutionStatus.CANCELED);
        assertThat(attempt.getStatus())
                .isEqualTo(StateExecutionAttemptStatus.CANCELED);
        assertThat(attempt.getHeartbeatAt()).isNull();
        verify(workflowExecutionRepository).save(execution);
        verify(executionScopeRepository).save(root);
        verify(stateExecutionRepository).save(state);
        verify(attemptRepository).save(attempt);
    }

    @Test
    void leavesCompletedExecutionUnchanged() {
        WorkflowExecution execution =
                execution(WorkflowExecutionStatus.SUCCEEDED);
        when(workflowExecutionRepository.findByIdForUpdate(execution.getId()))
                .thenReturn(Optional.of(execution));

        var response = service.cancelExecution(
                execution.getWorkflow().getId(),
                execution.getId()
        );

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        verify(executionScopeRepository, never())
                .findByWorkflowExecutionOrderByScopePathAsc(execution);
        verify(workflowExecutionRepository, never()).save(execution);
    }

    private WorkflowExecution execution(WorkflowExecutionStatus status) {
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID());
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID());
        execution.setWorkflow(workflow);
        execution.setStatus(status);
        return execution;
    }
}
