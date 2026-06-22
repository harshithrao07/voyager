package com.job.scheduler.scheduler;

import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.service.WorkflowTimeoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowDeadlineWatchdogServiceTest {
    @Mock
    private WorkflowTimeoutService workflowTimeoutService;
    @Mock
    private WorkflowExecutionRunner workflowExecutionRunner;

    private WorkflowDeadlineWatchdogService watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new WorkflowDeadlineWatchdogService(
                workflowTimeoutService,
                workflowExecutionRunner
        );
        ReflectionTestUtils.setField(watchdog, "claimLimit", 25);
    }

    @Test
    void timesOutRootAndResumesParentAfterNestedTimeout() {
        UUID executionId = UUID.randomUUID();
        UUID childScopeId = UUID.randomUUID();
        UUID parentScopeId = UUID.randomUUID();
        when(workflowTimeoutService.overdueRootExecutionIds(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(executionId));
        when(workflowTimeoutService.overdueNestedScopeIds(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(childScopeId));
        when(workflowTimeoutService.timeoutNestedScope(
                eq(childScopeId),
                any(Instant.class)
        )).thenReturn(Optional.of(
                new WorkflowTimeoutService.NestedTimeoutResume(
                        executionId,
                        parentScopeId
                )
        ));

        watchdog.timeoutOverdueMachines();

        verify(workflowTimeoutService).timeoutRootExecution(
                eq(executionId),
                any(Instant.class)
        );
        verify(workflowExecutionRunner).resume(executionId, parentScopeId);
    }
}
