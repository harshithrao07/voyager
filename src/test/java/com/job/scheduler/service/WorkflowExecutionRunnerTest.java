package com.job.scheduler.service;

import com.job.scheduler.dto.StartWorkflowExecutionRequestDTO;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.workflow.asl.runtime.CreatedWorkflowExecution;
import com.job.scheduler.workflow.asl.runtime.ExecutionScopeCoordinator;
import com.job.scheduler.workflow.asl.runtime.InterpreterOutcome;
import com.job.scheduler.workflow.asl.runtime.WorkflowInterpreter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionRunnerTest {
    @Mock
    private WorkflowExecutionService workflowExecutionService;
    @Mock
    private WorkflowInterpreter workflowInterpreter;
    @Mock
    private ExecutionScopeCoordinator scopeCoordinator;

    private ObjectMapper objectMapper;
    private WorkflowExecutionRunner runner;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        runner = new WorkflowExecutionRunner(
                workflowExecutionService,
                workflowInterpreter,
                scopeCoordinator,
                objectMapper,
                10
        );
    }

    @Test
    void drivesInlineStatesUntilExecutionSucceeds() {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID rootScopeId = UUID.randomUUID();
        var input = objectMapper.createObjectNode().put("total", 125);
        var routed = objectMapper.createObjectNode().put("route", "approved");
        var output = objectMapper.createObjectNode().put("approved", true);
        when(workflowExecutionService.createExecution(
                eq(workflowId),
                eq(input),
                eq(null)
        )).thenReturn(new CreatedWorkflowExecution(
                executionId,
                rootScopeId
        ));
        when(workflowInterpreter.advance(rootScopeId))
                .thenReturn(
                        new InterpreterOutcome.Continued("Done", routed),
                        new InterpreterOutcome.Succeeded(output)
                );

        var response = runner.start(
                workflowId,
                new StartWorkflowExecutionRequestDTO(input)
        );

        assertThat(response.workflowExecutionId()).isEqualTo(executionId);
        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        assertThat(response.output()).isEqualTo(output);
        verify(workflowInterpreter, times(2)).advance(rootScopeId);
    }

    @Test
    void returnsPersistedInterpreterFailure() {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID rootScopeId = UUID.randomUUID();
        when(workflowExecutionService.createExecution(
                eq(workflowId),
                any(),
                eq(null)
        )).thenReturn(new CreatedWorkflowExecution(
                executionId,
                rootScopeId
        ));
        when(workflowInterpreter.advance(rootScopeId))
                .thenReturn(new InterpreterOutcome.Failed(
                        "Order.Invalid",
                        "Order rejected"
                ));

        var response = runner.start(workflowId, null);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(response.error()).isEqualTo("Order.Invalid");
        assertThat(response.cause()).isEqualTo("Order rejected");
    }

    @Test
    void stopsDrivingWhenInterpreterPersistsFutureWait() {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID rootScopeId = UUID.randomUUID();
        Instant wakeAt = Instant.now().plusSeconds(60);
        when(workflowExecutionService.createExecution(
                eq(workflowId),
                any(),
                eq(null)
        )).thenReturn(new CreatedWorkflowExecution(
                executionId,
                rootScopeId
        ));
        when(workflowInterpreter.advance(rootScopeId))
                .thenReturn(new InterpreterOutcome.Waiting(wakeAt));

        var response = runner.start(workflowId, null);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.WAITING);
        assertThat(response.wakeAt()).isEqualTo(wakeAt);
        verify(workflowInterpreter).advance(rootScopeId);
    }

    @Test
    void returnsWaitingWhenTaskRetryIsScheduled() {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID rootScopeId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant availableAt = Instant.now().plusSeconds(5);
        when(workflowExecutionService.createExecution(
                eq(workflowId),
                any(),
                eq(null)
        )).thenReturn(new CreatedWorkflowExecution(
                executionId,
                rootScopeId
        ));
        when(workflowInterpreter.advance(rootScopeId))
                .thenReturn(new InterpreterOutcome.RetryScheduled(
                        attemptId,
                        availableAt
                ));

        var response = runner.start(workflowId, null);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.WAITING);
        assertThat(response.wakeAt()).isEqualTo(availableAt);
        assertThat(response.stateExecutionAttemptId()).isEqualTo(attemptId);
    }

    @Test
    void stopsInfiniteInlineTransitionLoops() {
        runner = new WorkflowExecutionRunner(
                workflowExecutionService,
                workflowInterpreter,
                scopeCoordinator,
                objectMapper,
                2
        );
        UUID workflowId = UUID.randomUUID();
        UUID rootScopeId = UUID.randomUUID();
        when(workflowExecutionService.createExecution(
                eq(workflowId),
                any(),
                eq(null)
        )).thenReturn(new CreatedWorkflowExecution(
                UUID.randomUUID(),
                rootScopeId
        ));
        when(workflowInterpreter.advance(rootScopeId))
                .thenReturn(new InterpreterOutcome.Continued(
                        "Loop",
                        objectMapper.createObjectNode()
                ));

        assertThatThrownBy(() -> runner.start(workflowId, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Workflow exceeded the maximum inline transition limit"
                );
    }
}
