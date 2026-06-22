package com.job.scheduler.scheduler;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.service.WorkflowSchedulingService;
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
public class PendingWorkflowExecutionSchedulerService {
    private final ExecutionScopeRepository executionScopeRepository;
    private final WorkflowExecutionRunner workflowExecutionRunner;
    private final WorkflowSchedulingService workflowSchedulingService;

    @Value("${scheduler.workflow.start-claim-limit:100}")
    private int claimLimit;

    @Value("${scheduler.workflow.start-claim-timeout-ms:60000}")
    private long claimTimeoutMs;

    @Scheduled(
            fixedDelayString =
                    "${scheduler.workflow.start-poll-delay-ms:1000}"
    )
    public void startPendingExecutions() {
        List<ExecutionScope> roots =
                executionScopeRepository.claimPendingExecutionRoots(
                        Instant.now().minusMillis(claimTimeoutMs),
                        claimLimit
                );
        for (ExecutionScope root : roots) {
            try {
                workflowExecutionRunner.resume(
                        root.getWorkflowExecution().getId(),
                        root.getId()
                );
            } catch (RuntimeException exception) {
                workflowSchedulingService.releaseFailedStartClaim(root.getId());
            }
        }
    }
}
