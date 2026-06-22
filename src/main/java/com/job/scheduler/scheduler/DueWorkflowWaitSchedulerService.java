package com.job.scheduler.scheduler;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.service.WorkflowWaitService;
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
public class DueWorkflowWaitSchedulerService {
    private final ExecutionScopeRepository executionScopeRepository;
    private final WorkflowExecutionRunner workflowExecutionRunner;
    private final WorkflowWaitService workflowWaitService;

    @Value("${scheduler.workflow.wait-claim-limit:100}")
    private int claimLimit;

    @Value("${scheduler.workflow.wait-resume-retry-delay-ms:5000}")
    private long retryDelayMs;

    @Value("${scheduler.workflow.wait-claim-timeout-ms:60000}")
    private long claimTimeoutMs;

    @Scheduled(
            fixedDelayString =
                    "${scheduler.workflow.wait-poll-delay-ms:1000}"
    )
    public void resumeDueScopes() {
        Instant now = Instant.now();
        List<ExecutionScope> dueScopes =
                executionScopeRepository.claimDueWaitingScopes(
                        now,
                        now.minusMillis(claimTimeoutMs),
                        claimLimit
                );
        for (ExecutionScope scope : dueScopes) {
            try {
                workflowExecutionRunner.resume(
                        scope.getWorkflowExecution().getId(),
                        scope.getId()
                );
            } catch (RuntimeException exception) {
                workflowWaitService.releaseFailedClaim(
                        scope.getId(),
                        Instant.now().plusMillis(retryDelayMs)
                );
            }
        }
    }
}
