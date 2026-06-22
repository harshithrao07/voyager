package com.job.scheduler.scheduler;

import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.service.WorkflowTimeoutService;
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
public class WorkflowDeadlineWatchdogService {
    private final WorkflowTimeoutService workflowTimeoutService;
    private final WorkflowExecutionRunner workflowExecutionRunner;

    @Value("${scheduler.workflow.deadline-claim-limit:100}")
    private int claimLimit;

    @Scheduled(
            fixedDelayString =
                    "${scheduler.workflow.deadline-poll-delay-ms:1000}"
    )
    public void timeoutOverdueMachines() {
        Instant now = Instant.now();
        workflowTimeoutService.overdueRootExecutionIds(now, claimLimit)
                .forEach(executionId ->
                        workflowTimeoutService.timeoutRootExecution(
                                executionId,
                                now
                        ));

        workflowTimeoutService.overdueNestedScopeIds(now, claimLimit)
                .forEach(scopeId ->
                        workflowTimeoutService.timeoutNestedScope(scopeId, now)
                                .ifPresent(resume ->
                                        workflowExecutionRunner.resume(
                                                resume.workflowExecutionId(),
                                                resume.parentScopeId()
                                        )));
    }
}
