package com.job.scheduler.service;

import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.FunctionInvocationRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionRetentionServiceTest {
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private StateExecutionAttemptRepository attemptRepository;
    @Mock
    private StateExecutionRepository stateExecutionRepository;
    @Mock
    private ExecutionScopeRepository executionScopeRepository;
    @Mock
    private FunctionInvocationRepository functionInvocationRepository;

    private WorkflowExecutionRetentionService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowExecutionRetentionService(
                workflowExecutionRepository,
                attemptRepository,
                stateExecutionRepository,
                executionScopeRepository,
                functionInvocationRepository
        );
    }

    @Test
    void deletesClaimedExecutionTreesInForeignKeyOrder() {
        Instant cutoff = Instant.parse("2026-06-01T00:00:00Z");
        List<UUID> executionIds = List.of(
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        when(workflowExecutionRepository
                .claimExpiredTerminalExecutionIds(cutoff, 1_000))
                .thenReturn(executionIds);
        when(functionInvocationRepository
                .deleteByWorkflowExecutionIds(executionIds)).thenReturn(3);
        when(attemptRepository
                .deleteByWorkflowExecutionIds(executionIds)).thenReturn(5);
        when(stateExecutionRepository
                .deleteByWorkflowExecutionIds(executionIds)).thenReturn(4);
        when(executionScopeRepository
                .deleteByWorkflowExecutionIds(executionIds)).thenReturn(3);
        when(workflowExecutionRepository
                .deleteRetainedExecutions(executionIds, cutoff)).thenReturn(2);

        var result = service.deleteCompletedBefore(cutoff, 5_000);

        assertThat(result).isEqualTo(
                new WorkflowExecutionRetentionService.RetentionResult(
                        2,
                        3,
                        4,
                        5,
                        3
                )
        );
        InOrder deletionOrder = inOrder(
                functionInvocationRepository,
                attemptRepository,
                stateExecutionRepository,
                executionScopeRepository,
                workflowExecutionRepository
        );
        deletionOrder.verify(functionInvocationRepository)
                .deleteByWorkflowExecutionIds(executionIds);
        deletionOrder.verify(attemptRepository)
                .deleteByWorkflowExecutionIds(executionIds);
        deletionOrder.verify(stateExecutionRepository)
                .deleteByWorkflowExecutionIds(executionIds);
        deletionOrder.verify(executionScopeRepository)
                .deleteByWorkflowExecutionIds(executionIds);
        deletionOrder.verify(workflowExecutionRepository)
                .deleteRetainedExecutions(executionIds, cutoff);
    }

    @Test
    void returnsWithoutDeletingWhenNothingIsEligible() {
        Instant cutoff = Instant.parse("2026-06-01T00:00:00Z");
        when(workflowExecutionRepository
                .claimExpiredTerminalExecutionIds(cutoff, 25))
                .thenReturn(List.of());

        var result = service.deleteCompletedBefore(cutoff, 25);

        assertThat(result).isEqualTo(
                WorkflowExecutionRetentionService.RetentionResult.empty()
        );
        verifyNoInteractions(
                attemptRepository,
                stateExecutionRepository,
                executionScopeRepository,
                functionInvocationRepository
        );
    }

    @Test
    void rejectsNonPositiveBatchSizes() {
        assertThatThrownBy(() ->
                service.deleteCompletedBefore(Instant.now(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch size");
        verifyNoInteractions(
                workflowExecutionRepository,
                attemptRepository,
                stateExecutionRepository,
                executionScopeRepository,
                functionInvocationRepository
        );
    }
}
