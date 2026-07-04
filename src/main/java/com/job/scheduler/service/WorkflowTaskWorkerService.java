package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowTaskDispatchEvent;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.workflow.asl.runtime.InterpreterOutcome;
import com.job.scheduler.workflow.asl.runtime.WorkflowInterpreter;
import com.job.scheduler.workflow.task.TaskExecutionContext;
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
                .findByIdWithWorkerContext(event.stateExecutionAttemptId())
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed task attempt does not exist"
                ));
        WorkerAttemptContext context = contextFor(attempt);
        final JsonNode result;
        try (TaskAttemptHeartbeatService.HeartbeatLease ignored =
                     heartbeatService.start(
                             attempt.getId(),
                             workerId,
                             context.heartbeatSeconds()
                     )) {
            result = resourceRouter.execute(
                    context.resource(),
                    readJson(context.arguments()),
                    new TaskExecutionContext(
                            context.workflowExecutionId(),
                            context.stateName()
                    )
            );
        } catch (TaskResourceException exception) {
            InterpreterOutcome outcome = workflowInterpreter.completeTaskFailure(
                    attempt.getId(),
                    exception.error(),
                    causeOf(exception)
            );
            resumeAfterCatch(context, outcome);
            return;
        } catch (RuntimeException exception) {
            InterpreterOutcome outcome = workflowInterpreter.completeTaskFailure(
                    attempt.getId(),
                    taskError(exception),
                    exception.getMessage()
            );
            resumeAfterCatch(context, outcome);
            return;
        }

        InterpreterOutcome outcome =
                workflowInterpreter.completeTaskSuccess(
                        attempt.getId(),
                        result
                );
        resumeAfterCatch(context, outcome);
    }

    private void resumeAfterCatch(
            WorkerAttemptContext context,
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
        executionRunner.resume(
                context.workflowExecutionId(),
                context.executionScopeId()
        );
    }

    private WorkerAttemptContext contextFor(StateExecutionAttempt attempt) {
        var stateExecution = attempt.getStateExecution();
        var scope = stateExecution.getExecutionScope();
        return new WorkerAttemptContext(
                stateExecution.getResource(),
                attempt.getArguments(),
                attempt.getHeartbeatSeconds(),
                scope.getWorkflowExecution().getId(),
                scope.getId(),
                stateExecution.getStateName()
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

    private record WorkerAttemptContext(
            String resource,
            String arguments,
            Long heartbeatSeconds,
            java.util.UUID workflowExecutionId,
            java.util.UUID executionScopeId,
            String stateName
    ) {
    }
}
