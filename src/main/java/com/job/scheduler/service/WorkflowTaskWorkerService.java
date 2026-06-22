package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowTaskDispatchEvent;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.workflow.asl.runtime.InterpreterOutcome;
import com.job.scheduler.workflow.asl.runtime.WorkflowInterpreter;
import com.job.scheduler.workflow.task.TaskResourceException;
import com.job.scheduler.workflow.task.TaskResourceRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class WorkflowTaskWorkerService {
    private final StateExecutionAttemptQueueService queueService;
    private final StateExecutionAttemptRepository attemptRepository;
    private final TaskResourceRouter resourceRouter;
    private final WorkflowInterpreter workflowInterpreter;
    private final WorkflowExecutionRunner executionRunner;
    private final TaskAttemptHeartbeatService heartbeatService;
    private final ObjectMapper objectMapper;

    @Value("${scheduler.worker-id}")
    private String workerId;

    public void process(WorkflowTaskDispatchEvent event) {
        if (!queueService.claimForExecution(
                event.stateExecutionAttemptId(),
                workerId
        )) {
            return;
        }

        StateExecutionAttempt attempt = attemptRepository
                .findById(event.stateExecutionAttemptId())
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed task attempt does not exist"
                ));
        final JsonNode result;
        try (TaskAttemptHeartbeatService.HeartbeatLease ignored =
                     heartbeatService.start(
                             attempt.getId(),
                             workerId,
                             attempt.getHeartbeatSeconds()
                     )) {
            // READER/WRITER attempts carry their own resource; the fork
            // StateExecution they hang on has none.
            String resource = attempt.getResource() != null
                    ? attempt.getResource()
                    : attempt.getStateExecution().getResource();
            result = resourceRouter.execute(
                    resource,
                    readJson(attempt.getArguments())
            );
        } catch (TaskResourceException exception) {
            InterpreterOutcome outcome = workflowInterpreter.completeTaskFailure(
                    attempt.getId(),
                    exception.error(),
                    causeOf(exception)
            );
            resumeAfterCatch(attempt, outcome);
            return;
        } catch (RuntimeException exception) {
            InterpreterOutcome outcome = workflowInterpreter.completeTaskFailure(
                    attempt.getId(),
                    taskError(exception),
                    exception.getMessage()
            );
            resumeAfterCatch(attempt, outcome);
            return;
        }

        InterpreterOutcome outcome =
                workflowInterpreter.completeTaskSuccess(
                        attempt.getId(),
                        result
                );
        resumeAfterCatch(attempt, outcome);
    }

    private void resumeAfterCatch(
            StateExecutionAttempt attempt,
            InterpreterOutcome outcome
    ) {
        // Continued: the scope advanced to its next state. Succeeded/Failed: the
        // scope settled, which may unblock a parent Parallel/Map join. In all
        // three the driver must run; Dispatched/RetryScheduled/Waiting mean the
        // scope re-suspended and another trigger will resume it.
        boolean shouldResume = outcome instanceof InterpreterOutcome.Continued
                || outcome instanceof InterpreterOutcome.Succeeded
                || outcome instanceof InterpreterOutcome.Failed;
        if (!shouldResume) {
            return;
        }
        var scope = attempt.getStateExecution().getExecutionScope();
        executionRunner.resume(
                scope.getWorkflowExecution().getId(),
                scope.getId()
        );
    }

    private JsonNode readJson(String value) {
        try {
            return value == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read persisted Task arguments",
                    exception
            );
        }
    }

    private String taskError(RuntimeException exception) {
        return exception instanceof IllegalArgumentException
                ? "States.TaskFailed"
                : exception.getClass().getSimpleName();
    }

    /**
     * The cause surfaced to {@code $states.errorOutput.Cause}: structured detail
     * serialized to JSON when present, otherwise the failure message.
     */
    private String causeOf(TaskResourceException exception) {
        if (exception.detail() != null) {
            try {
                return objectMapper.writeValueAsString(exception.detail());
            } catch (RuntimeException ignored) {
                // Fall back to the message if the detail cannot be serialized.
            }
        }
        return exception.getMessage();
    }
}
