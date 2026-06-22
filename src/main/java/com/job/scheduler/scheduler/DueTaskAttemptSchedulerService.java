package com.job.scheduler.scheduler;

import com.job.scheduler.dto.WorkflowTaskDispatchEvent;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.producers.WorkflowTaskQueueProducer;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.service.StateExecutionAttemptQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DueTaskAttemptSchedulerService {
    private final StateExecutionAttemptRepository attemptRepository;
    private final WorkflowTaskQueueProducer queueProducer;
    private final StateExecutionAttemptQueueService queueService;

    @Value("${scheduler.workflow.task-dispatch-claim-limit:100}")
    private int claimLimit;

    @Scheduled(
            fixedDelayString =
                    "${scheduler.workflow.task-dispatch-poll-delay-ms:1000}"
    )
    public void dispatchDueAttempts() {
        List<StateExecutionAttempt> attempts =
                attemptRepository.claimDueAttemptsForDispatch(
                        Instant.now(),
                        claimLimit
                );
        for (StateExecutionAttempt attempt : attempts) {
            queueProducer.send(new WorkflowTaskDispatchEvent(attempt.getId()))
                    .whenComplete((ignored, exception) -> {
                        if (exception != null) {
                            queueService.recordDispatchUncertain(
                                    attempt.getId(),
                                    exception.getMessage()
                            );
                        }
                    });
        }
    }
}
