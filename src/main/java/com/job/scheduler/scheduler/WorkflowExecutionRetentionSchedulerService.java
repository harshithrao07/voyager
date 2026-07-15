package com.job.scheduler.scheduler;

import com.job.scheduler.service.WorkflowExecutionRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "scheduler.workflow.retention.enabled",
        havingValue = "true"
)
public class WorkflowExecutionRetentionSchedulerService {
    private final WorkflowExecutionRetentionService retentionService;

    @Value("${scheduler.workflow.retention-age-ms:2592000000}")
    private long retentionAgeMs;

    @Value("${scheduler.workflow.retention-batch-size:100}")
    private int batchSize;

    @Scheduled(
            fixedDelayString =
                    "${scheduler.workflow.retention-poll-delay-ms:3600000}",
            initialDelayString =
                    "${scheduler.workflow.retention-initial-delay-ms:60000}"
    )
    public void deleteExpiredExecutions() {
        if (retentionAgeMs < 1) {
            throw new IllegalStateException(
                    "Workflow retention age must be positive"
            );
        }
        if (batchSize < 1) {
            throw new IllegalStateException(
                    "Workflow retention batch size must be positive"
            );
        }
        Instant cutoff = Instant.now().minusMillis(retentionAgeMs);
        var result = retentionService.deleteCompletedBefore(
                cutoff,
                batchSize
        );
        if (result.executions() > 0) {
            log.info(
                    "Deleted {} workflow executions, {} scopes, {} state "
                            + "executions, {} attempts, and {} function "
                            + "invocations completed before {}",
                    result.executions(),
                    result.scopes(),
                    result.stateExecutions(),
                    result.attempts(),
                    result.functionInvocations(),
                    cutoff
            );
        }
    }
}
