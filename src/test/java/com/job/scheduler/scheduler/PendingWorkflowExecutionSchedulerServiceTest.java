package com.job.scheduler.scheduler;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.service.WorkflowSchedulingService;
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
class PendingWorkflowExecutionSchedulerServiceTest {
    @Mock
    private ExecutionScopeRepository executionScopeRepository;
    @Mock
    private WorkflowExecutionRunner workflowExecutionRunner;
    @Mock
    private WorkflowSchedulingService workflowSchedulingService;

    private PendingWorkflowExecutionSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PendingWorkflowExecutionSchedulerService(
                executionScopeRepository,
                workflowExecutionRunner,
                workflowSchedulingService
        );
        ReflectionTestUtils.setField(scheduler, "claimLimit", 25);
        ReflectionTestUtils.setField(scheduler, "claimTimeoutMs", 60000L);
    }

    @Test
    void claimsAndStartsPendingExecution() {
        ExecutionScope root = root();
        when(executionScopeRepository.claimPendingExecutionRoots(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(root));

        scheduler.startPendingExecutions();

        verify(workflowExecutionRunner).resume(
                root.getWorkflowExecution().getId(),
                root.getId()
        );
    }

    @Test
    void releasesClaimWhenStartFails() {
        ExecutionScope root = root();
        when(executionScopeRepository.claimPendingExecutionRoots(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(root));
        doThrow(new IllegalStateException("temporary failure"))
                .when(workflowExecutionRunner)
                .resume(
                        root.getWorkflowExecution().getId(),
                        root.getId()
                );

        scheduler.startPendingExecutions();

        verify(workflowSchedulingService)
                .releaseFailedStartClaim(root.getId());
    }

    private ExecutionScope root() {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID());
        ExecutionScope root = new ExecutionScope();
        root.setId(UUID.randomUUID());
        root.setWorkflowExecution(execution);
        return root;
    }
}
