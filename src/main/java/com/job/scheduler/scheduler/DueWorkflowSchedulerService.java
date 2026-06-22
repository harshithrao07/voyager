package com.job.scheduler.scheduler;

import com.job.scheduler.service.WorkflowSchedulingService;
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
public class DueWorkflowSchedulerService {
    private final WorkflowSchedulingService workflowSchedulingService;

    @Value("${scheduler.workflow.schedule-claim-limit:100}")
    private int claimLimit;

    @Scheduled(
            fixedDelayString =
                    "${scheduler.workflow.schedule-poll-delay-ms:1000}"
    )
    public void materializeDueExecutions() {
        workflowSchedulingService.materializeDueExecutions(
                Instant.now(),
                claimLimit
        );
    }
}
