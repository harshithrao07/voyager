package com.job.scheduler.scheduler;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.workflow.asl.runtime.ExecutionScopeCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Recovers the crash window after a state transition commits its next-state
 * cursor but before the in-process runner drives that cursor.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class StaleWorkflowScopeRecoverySchedulerService {
    private final ExecutionScopeRepository executionScopeRepository;
    private final WorkflowExecutionRunner workflowExecutionRunner;
    private final ExecutionScopeCoordinator executionScopeCoordinator;

    @Value("${scheduler.workflow.scope-recovery-claim-limit:100}")
    private int claimLimit;

    @Value("${scheduler.workflow.scope-recovery-stale-timeout-ms:60000}")
    private long staleTimeoutMs;

    @Scheduled(
            fixedDelayString =
                    "${scheduler.workflow.scope-recovery-poll-delay-ms:1000}"
    )
    public void recoverStaleRunnableScopes() {
        Instant staleBefore = Instant.now().minusMillis(staleTimeoutMs);
        recoverLostChildSettlements(staleBefore);
        List<ExecutionScope> scopes =
                executionScopeRepository.claimStaleRunnableScopes(
                        staleBefore,
                        claimLimit
                );
        for (ExecutionScope scope : scopes) {
            try {
                workflowExecutionRunner.resume(
                        scope.getWorkflowExecution().getId(),
                        scope.getId()
                );
            } catch (RuntimeException ignored) {
                // The claim refreshed updated_at. A transient failure becomes
                // eligible again after the configured stale timeout.
            }
        }
    }

    private void recoverLostChildSettlements(Instant staleBefore) {
        List<ExecutionScope> children =
                executionScopeRepository.claimStaleSettledChildren(
                        staleBefore,
                        claimLimit
                );
        for (ExecutionScope child : children) {
            try {
                executionScopeCoordinator.onChildSettled(child.getId())
                        .ifPresent(parentScopeId ->
                                workflowExecutionRunner.resume(
                                        child.getWorkflowExecution().getId(),
                                        parentScopeId
                                ));
            } catch (RuntimeException ignored) {
                // The child claim refreshed updated_at. If parent readiness was
                // not persisted, the child is eligible again after the stale
                // timeout. If it was persisted, runnable-scope recovery owns
                // the remaining crash window.
            }
        }
    }
}
