package com.job.scheduler.scheduler;

import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.workflow.asl.runtime.WorkflowInterpreter;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import com.job.scheduler.workflow.asl.runtime.InterpreterOutcome;

@ExtendWith(MockitoExtension.class)
class RunningTaskAttemptWatchdogServiceTest {
    @Mock
    private StateExecutionAttemptRepository attemptRepository;
    @Mock
    private WorkflowInterpreter workflowInterpreter;
    @Mock
    private WorkflowExecutionRunner executionRunner;

    private RunningTaskAttemptWatchdogService watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new RunningTaskAttemptWatchdogService(
                attemptRepository,
                workflowInterpreter,
                executionRunner
        );
        ReflectionTestUtils.setField(watchdog, "runningTimeoutMs", 600000L);
    }

    @Test
    void timesOutStaleRunningAttempts() {
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setId(UUID.randomUUID());
        when(attemptRepository.findRunningWithoutAslDeadlineBefore(
                eq(StateExecutionAttemptStatus.RUNNING),
                any(Instant.class)
        )).thenReturn(List.of(attempt));

        watchdog.timeoutStaleRunningAttempts();

        verify(workflowInterpreter).completeTaskTimeout(
                attempt.getId(),
                "Task worker exceeded the platform running-attempt timeout"
        );
    }

    @Test
    void resumesWorkflowWhenAslTimeoutIsCaught() {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID());
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(execution);
        StateExecution stateExecution = new StateExecution();
        stateExecution.setExecutionScope(scope);
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setStateExecution(stateExecution);
        when(attemptRepository.findByStatusAndTimeoutAtLessThanEqual(
                eq(StateExecutionAttemptStatus.RUNNING),
                any(Instant.class)
        )).thenReturn(List.of(attempt));
        when(workflowInterpreter.completeTaskTimeout(
                attempt.getId(),
                "Task exceeded its ASL TimeoutSeconds"
        )).thenReturn(new InterpreterOutcome.Continued(
                "Recovered",
                null
        ));

        watchdog.timeoutStaleRunningAttempts();

        verify(executionRunner).resume(execution.getId(), scope.getId());
        verify(workflowInterpreter, never()).completeTaskTimeout(
                attempt.getId(),
                "Task missed its ASL HeartbeatSeconds deadline"
        );
    }
}
