package com.job.scheduler.scheduler;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.workflow.asl.runtime.ExecutionScopeCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaleWorkflowScopeRecoverySchedulerServiceTest {
    @Mock
    private ExecutionScopeRepository executionScopeRepository;
    @Mock
    private WorkflowExecutionRunner workflowExecutionRunner;
    @Mock
    private ExecutionScopeCoordinator executionScopeCoordinator;

    private StaleWorkflowScopeRecoverySchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new StaleWorkflowScopeRecoverySchedulerService(
                executionScopeRepository,
                workflowExecutionRunner,
                executionScopeCoordinator
        );
        ReflectionTestUtils.setField(scheduler, "claimLimit", 25);
        ReflectionTestUtils.setField(scheduler, "staleTimeoutMs", 60000L);
    }

    @Test
    void claimsAndResumesPersistedNextState() {
        ExecutionScope scope = scope();
        when(executionScopeRepository.claimStaleRunnableScopes(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(scope));
        when(executionScopeRepository.claimStaleSettledChildren(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of());

        scheduler.recoverStaleRunnableScopes();

        verify(workflowExecutionRunner).resume(
                scope.getWorkflowExecution().getId(),
                scope.getId()
        );
    }

    @Test
    void leavesFailedRecoveryEligibleForAnotherStaleClaim() {
        ExecutionScope scope = scope();
        when(executionScopeRepository.claimStaleRunnableScopes(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(scope));
        when(executionScopeRepository.claimStaleSettledChildren(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of());
        doThrow(new IllegalStateException("temporary failure"))
                .when(workflowExecutionRunner)
                .resume(
                        scope.getWorkflowExecution().getId(),
                        scope.getId()
                );

        scheduler.recoverStaleRunnableScopes();

        verify(workflowExecutionRunner).resume(
                scope.getWorkflowExecution().getId(),
                scope.getId()
        );
    }

    @Test
    void replaysLostChildSettlementBeforeRunnableScopes() {
        ExecutionScope child = scope();
        UUID parentScopeId = UUID.randomUUID();
        when(executionScopeRepository.claimStaleSettledChildren(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(child));
        when(executionScopeRepository.claimStaleRunnableScopes(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of());
        when(executionScopeCoordinator.onChildSettled(child.getId()))
                .thenReturn(Optional.of(parentScopeId));

        scheduler.recoverStaleRunnableScopes();

        verify(workflowExecutionRunner).resume(
                child.getWorkflowExecution().getId(),
                parentScopeId
        );
    }

    private ExecutionScope scope() {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID());
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(execution);
        return scope;
    }
}
