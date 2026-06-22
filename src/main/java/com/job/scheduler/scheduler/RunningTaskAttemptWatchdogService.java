package com.job.scheduler.scheduler;

import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.workflow.asl.runtime.InterpreterOutcome;
import com.job.scheduler.workflow.asl.runtime.WorkflowInterpreter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RunningTaskAttemptWatchdogService {
    private final StateExecutionAttemptRepository attemptRepository;
    private final WorkflowInterpreter workflowInterpreter;
    private final WorkflowExecutionRunner executionRunner;

    @Value("${scheduler.workflow.task-running-timeout-ms:600000}")
    private long runningTimeoutMs;

    @Scheduled(
            fixedDelayString =
                    "${scheduler.workflow.task-running-watchdog-poll-delay-ms:60000}"
    )
    public void timeoutStaleRunningAttempts() {
        Instant now = Instant.now();
        Map<UUID, TimedOutAttempt> staleAttempts = new LinkedHashMap<>();
        attemptRepository.findByStatusAndTimeoutAtLessThanEqual(
                        StateExecutionAttemptStatus.RUNNING,
                        now
                )
                .forEach(attempt -> staleAttempts.put(
                        attempt.getId(),
                        new TimedOutAttempt(
                                attempt,
                                "Task exceeded its ASL TimeoutSeconds"
                        )
                ));
        attemptRepository.findByStatusAndHeartbeatDeadlineAtLessThanEqual(
                        StateExecutionAttemptStatus.RUNNING,
                        now
                )
                .forEach(attempt -> staleAttempts.putIfAbsent(
                        attempt.getId(),
                        new TimedOutAttempt(
                                attempt,
                                "Task missed its ASL HeartbeatSeconds deadline"
                        )
                ));
        attemptRepository.findRunningWithoutAslDeadlineBefore(
                        StateExecutionAttemptStatus.RUNNING,
                        now.minusMillis(runningTimeoutMs)
                )
                .forEach(attempt -> staleAttempts.putIfAbsent(
                        attempt.getId(),
                        new TimedOutAttempt(
                                attempt,
                                "Task worker exceeded the platform running-attempt timeout"
                        )
                ));

        staleAttempts.values().forEach(this::timeout);
    }

    private void timeout(TimedOutAttempt timedOut) {
        InterpreterOutcome outcome = workflowInterpreter.completeTaskTimeout(
                timedOut.attempt().getId(),
                timedOut.cause()
        );
        if (outcome instanceof InterpreterOutcome.Continued) {
            var scope = timedOut.attempt()
                    .getStateExecution()
                    .getExecutionScope();
            executionRunner.resume(
                    scope.getWorkflowExecution().getId(),
                    scope.getId()
            );
        }
    }

    private record TimedOutAttempt(
            StateExecutionAttempt attempt,
            String cause
    ) {
    }
}
