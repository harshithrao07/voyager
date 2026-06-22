package com.job.scheduler.scheduler;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.service.WorkflowWaitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DueWorkflowWaitSchedulerServiceTest {
    @Mock
    private ExecutionScopeRepository executionScopeRepository;
    @Mock
    private WorkflowExecutionRunner workflowExecutionRunner;
    @Mock
    private WorkflowWaitService workflowWaitService;

    private DueWorkflowWaitSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DueWorkflowWaitSchedulerService(
                executionScopeRepository,
                workflowExecutionRunner,
                workflowWaitService
        );
        ReflectionTestUtils.setField(scheduler, "claimLimit", 25);
        ReflectionTestUtils.setField(scheduler, "retryDelayMs", 5000L);
        ReflectionTestUtils.setField(scheduler, "claimTimeoutMs", 60000L);
    }

    @Test
    void claimsAndResumesDueScopes() {
        ExecutionScope scope = scope();
        when(executionScopeRepository.claimDueWaitingScopes(
                any(Instant.class),
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(scope));

        scheduler.resumeDueScopes();

        verify(workflowExecutionRunner).resume(
                scope.getWorkflowExecution().getId(),
                scope.getId()
        );
    }

    @Test
    void releasesClaimWhenResumeFails() {
        ExecutionScope scope = scope();
        when(executionScopeRepository.claimDueWaitingScopes(
                any(Instant.class),
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(scope));
        doThrow(new IllegalStateException("temporary failure"))
                .when(workflowExecutionRunner)
                .resume(
                        scope.getWorkflowExecution().getId(),
                        scope.getId()
                );

        scheduler.resumeDueScopes();

        verify(workflowWaitService).releaseFailedClaim(
                eq(scope.getId()),
                any(Instant.class)
        );
    }

    private ExecutionScope scope() {
        WorkflowExecution workflowExecution = new WorkflowExecution();
        workflowExecution.setId(UUID.randomUUID());
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(workflowExecution);
        return scope;
    }
}
