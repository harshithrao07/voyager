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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowTimeoutServiceTest {
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private ExecutionScopeRepository executionScopeRepository;
    @Mock
    private StateExecutionRepository stateExecutionRepository;
    @Mock
    private StateExecutionAttemptRepository attemptRepository;
    @Mock
    private AslDefinitionNavigator definitionNavigator;
    @Mock
    private ExecutionScopeCoordinator scopeCoordinator;

    private WorkflowTimeoutService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTimeoutService(
                workflowExecutionRepository,
                executionScopeRepository,
                stateExecutionRepository,
                attemptRepository,
                definitionNavigator,
                scopeCoordinator
        );
    }

    @Test
    void timesOutRootExecutionAndAllActiveRuntimeRows() {
        Instant now = Instant.parse("2026-06-21T10:05:00Z");
        WorkflowExecution execution = execution();
        execution.setDeadlineAt(now.minusSeconds(1));
        ExecutionScope root = scope(execution, ExecutionScopeType.ROOT);
        StateExecution state = new StateExecution();
        state.setStatus(StateExecutionStatus.RUNNING);
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setStatus(StateExecutionAttemptStatus.RUNNING);

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

        assertThat(service.timeoutRootExecution(execution.getId(), now))
                .isTrue();

        assertThat(execution.getStatus())
                .isEqualTo(WorkflowExecutionStatus.TIMED_OUT);
        assertThat(execution.getError()).isEqualTo("States.Timeout");
        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.TIMED_OUT);
        assertThat(state.getStatus()).isEqualTo(StateExecutionStatus.TIMED_OUT);
        assertThat(attempt.getStatus())
                .isEqualTo(StateExecutionAttemptStatus.TIMED_OUT);
        assertThat(attempt.getHeartbeatAt()).isNull();
        verify(workflowExecutionRepository).save(execution);
        verify(executionScopeRepository).save(root);
        verify(stateExecutionRepository).save(state);
        verify(attemptRepository).save(attempt);
    }

    @Test
    void timesOutNestedMachineAndReturnsParentResume() {
        Instant now = Instant.parse("2026-06-21T10:05:00Z");
        WorkflowExecution execution = execution();
        ExecutionScope child =
                scope(execution, ExecutionScopeType.PARALLEL_BRANCH);
        child.setStartedAt(now.minusSeconds(61));
        UUID parentScopeId = UUID.randomUUID();

        when(executionScopeRepository.findByIdForUpdate(child.getId()))
                .thenReturn(Optional.of(child));
        when(definitionNavigator.readMachine(child))
                .thenReturn(new ObjectMapper().createObjectNode()
                        .put("TimeoutSeconds", 60));
        when(executionScopeRepository
                .findByWorkflowExecutionAndScopePathStartingWithOrderByScopePathAsc(
                        execution,
                        child.getScopePath() + "/"
                )).thenReturn(List.of());
        when(stateExecutionRepository
                .findByExecutionScopeOrderBySequenceNumberAsc(child))
                .thenReturn(List.of());
        when(scopeCoordinator.onChildSettled(child.getId()))
                .thenReturn(Optional.of(parentScopeId));

        var resume = service.timeoutNestedScope(child.getId(), now)
                .orElseThrow();

        assertThat(child.getStatus())
                .isEqualTo(ExecutionScopeStatus.TIMED_OUT);
        assertThat(child.getError()).isEqualTo("States.Timeout");
        assertThat(resume.workflowExecutionId()).isEqualTo(execution.getId());
        assertThat(resume.parentScopeId()).isEqualTo(parentScopeId);
    }

    private WorkflowExecution execution() {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID());
        execution.setStatus(WorkflowExecutionStatus.RUNNING);
        return execution;
    }

    private ExecutionScope scope(
            WorkflowExecution execution,
            ExecutionScopeType type
    ) {
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(execution);
        scope.setScopeType(type);
        scope.setScopePath(
                type == ExecutionScopeType.ROOT
                        ? "root"
                        : "root/Fork/g1/branch-0"
        );
        scope.setStatus(ExecutionScopeStatus.RUNNING);
        return scope;
    }
}
