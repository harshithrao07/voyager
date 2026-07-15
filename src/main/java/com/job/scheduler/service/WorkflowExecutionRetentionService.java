package com.job.scheduler.service;

import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.FunctionInvocationRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowExecutionRetentionService {
    static final int MAX_BATCH_SIZE = 1_000;

    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final StateExecutionAttemptRepository attemptRepository;
    private final StateExecutionRepository stateExecutionRepository;
    private final ExecutionScopeRepository executionScopeRepository;
    private final FunctionInvocationRepository functionInvocationRepository;

    /**
     * Deletes one locked batch of old terminal execution trees.
     *
     * <p>The candidate claim and every child/parent delete share one database
     * transaction. A crash or failure therefore rolls the batch back, and a
     * later scheduler pass can claim it again. {@code SKIP LOCKED} permits
     * multiple scheduler nodes to clean different executions safely.
     */
    @Transactional
    public RetentionResult deleteCompletedBefore(
            Instant cutoff,
            int requestedBatchSize
    ) {
        Objects.requireNonNull(cutoff, "cutoff is required");
        if (requestedBatchSize < 1) {
            throw new IllegalArgumentException(
                    "Retention batch size must be positive"
            );
        }
        int batchSize = Math.min(requestedBatchSize, MAX_BATCH_SIZE);
        List<UUID> executionIds = workflowExecutionRepository
                .claimExpiredTerminalExecutionIds(cutoff, batchSize);
        if (executionIds.isEmpty()) {
            return RetentionResult.empty();
        }

        int functionInvocations = functionInvocationRepository
                .deleteByWorkflowExecutionIds(executionIds);
        int attempts = attemptRepository
                .deleteByWorkflowExecutionIds(executionIds);
        int stateExecutions = stateExecutionRepository
                .deleteByWorkflowExecutionIds(executionIds);
        int scopes = executionScopeRepository
                .deleteByWorkflowExecutionIds(executionIds);
        int executions = workflowExecutionRepository
                .deleteRetainedExecutions(executionIds, cutoff);

        if (executions != executionIds.size()) {
            throw new IllegalStateException(
                    "Retention candidate changed while its batch was locked"
            );
        }
        return new RetentionResult(
                executions,
                scopes,
                stateExecutions,
                attempts,
                functionInvocations
        );
    }

    public record RetentionResult(
            int executions,
            int scopes,
            int stateExecutions,
            int attempts,
            int functionInvocations
    ) {
        public static RetentionResult empty() {
            return new RetentionResult(0, 0, 0, 0, 0);
        }
    }
}
