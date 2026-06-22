package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowTaskDispatchEvent;
import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.workflow.asl.runtime.InterpreterOutcome;
import com.job.scheduler.workflow.asl.runtime.WorkflowInterpreter;
import com.job.scheduler.workflow.task.TaskResourceRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WorkflowTaskWorkerServiceTest {
    @Mock
    private StateExecutionAttemptQueueService queueService;
    @Mock
    private StateExecutionAttemptRepository attemptRepository;
    @Mock
    private TaskResourceRouter resourceRouter;
    @Mock
    private WorkflowInterpreter workflowInterpreter;
    @Mock
    private WorkflowExecutionRunner executionRunner;
    @Mock
    private TaskAttemptHeartbeatService heartbeatService;

    private ObjectMapper objectMapper;
    private WorkflowTaskWorkerService worker;
    private StateExecutionAttempt attempt;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        worker = new WorkflowTaskWorkerService(
                queueService,
                attemptRepository,
                resourceRouter,
                workflowInterpreter,
                executionRunner,
                heartbeatService,
                objectMapper
        );
        ReflectionTestUtils.setField(worker, "workerId", "worker-1");
        attempt = attempt();
        lenient().when(heartbeatService.start(
                attempt.getId(),
                "worker-1",
                attempt.getHeartbeatSeconds()
        )).thenReturn(() -> {
        });
    }

    @Test
    void ignoresDuplicateMessageWhenAttemptCannotBeClaimed() {
        when(queueService.claimForExecution(attempt.getId(), "worker-1"))
                .thenReturn(false);

        worker.process(new WorkflowTaskDispatchEvent(attempt.getId()));

        verify(attemptRepository, never()).findById(any());
        verify(resourceRouter, never()).execute(any(), any());
    }

    @Test
    void executesTaskPersistsSuccessAndResumesInterpreter() {
        var result = objectMapper.createObjectNode().put("ok", true);
        when(queueService.claimForExecution(attempt.getId(), "worker-1"))
                .thenReturn(true);
        when(attemptRepository.findById(attempt.getId()))
                .thenReturn(Optional.of(attempt));
        when(resourceRouter.execute(
                eq("scheduler://cleanup"),
                any()
        )).thenReturn(result);
        when(workflowInterpreter.completeTaskSuccess(attempt.getId(), result))
                .thenReturn(new InterpreterOutcome.Continued(
                        "Done",
                        result
                ));

        worker.process(new WorkflowTaskDispatchEvent(attempt.getId()));

        verify(executionRunner).resume(
                attempt.getStateExecution()
                        .getExecutionScope()
                        .getWorkflowExecution()
                        .getId(),
                attempt.getStateExecution().getExecutionScope().getId()
        );
    }

    @Test
    void persistsBasicTaskFailure() {
        when(queueService.claimForExecution(attempt.getId(), "worker-1"))
                .thenReturn(true);
        when(attemptRepository.findById(attempt.getId()))
                .thenReturn(Optional.of(attempt));
        when(resourceRouter.execute(any(), any()))
                .thenThrow(new IllegalArgumentException("bad arguments"));

        worker.process(new WorkflowTaskDispatchEvent(attempt.getId()));

        verify(workflowInterpreter).completeTaskFailure(
                attempt.getId(),
                "States.TaskFailed",
                "bad arguments"
        );
    }

    @Test
    void resumesInterpreterWhenTaskFailureIsCaught() {
        when(queueService.claimForExecution(attempt.getId(), "worker-1"))
                .thenReturn(true);
        when(attemptRepository.findById(attempt.getId()))
                .thenReturn(Optional.of(attempt));
        when(resourceRouter.execute(any(), any()))
                .thenThrow(new IllegalArgumentException("bad arguments"));
        when(workflowInterpreter.completeTaskFailure(
                attempt.getId(),
                "States.TaskFailed",
                "bad arguments"
        )).thenReturn(new InterpreterOutcome.Continued(
                "Recovered",
                objectMapper.createObjectNode().put("handled", true)
        ));

        worker.process(new WorkflowTaskDispatchEvent(attempt.getId()));

        verify(executionRunner).resume(
                attempt.getStateExecution()
                        .getExecutionScope()
                        .getWorkflowExecution()
                        .getId(),
                attempt.getStateExecution().getExecutionScope().getId()
        );
    }

    private StateExecutionAttempt attempt() {
        WorkflowExecution workflowExecution = new WorkflowExecution();
        workflowExecution.setId(UUID.randomUUID());
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(workflowExecution);
        StateExecution stateExecution = new StateExecution();
        stateExecution.setExecutionScope(scope);
        stateExecution.setResource("scheduler://cleanup");
        StateExecutionAttempt value = new StateExecutionAttempt();
        value.setId(UUID.randomUUID());
        value.setStateExecution(stateExecution);
        value.setArguments("{\"olderThanDays\":30}");
        return value;
    }
}
