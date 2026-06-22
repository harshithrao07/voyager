package com.job.scheduler.scheduler;

import com.job.scheduler.repository.StateExecutionAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class QueuedTaskAttemptWatchdogService {
    private final StateExecutionAttemptRepository attemptRepository;

    @Value("${scheduler.workflow.task-queued-timeout-ms:300000}")
    private long queuedTimeoutMs;

    @Value("${scheduler.workflow.task-queued-recovery-limit:100}")
    private int recoveryLimit;

    @Scheduled(
            fixedDelayString =
                    "${scheduler.workflow.task-queued-watchdog-poll-delay-ms:60000}"
    )
    public void recoverStaleQueuedAttempts() {
        Instant now = Instant.now();
        attemptRepository.recoverStaleQueuedAttempts(
                now.minusMillis(queuedTimeoutMs),
                now,
                recoveryLimit
        );
    }
}
