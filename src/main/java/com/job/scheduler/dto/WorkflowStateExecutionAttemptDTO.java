package com.job.scheduler.dto;

import com.job.scheduler.enums.StateExecutionAttemptStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record WorkflowStateExecutionAttemptDTO(
        UUID id,
        int attemptNumber,
        StateExecutionAttemptStatus status,
        JsonNode arguments,
        JsonNode result,
        String workerId,
        Instant availableAt,
        Instant queuedAt,
        Instant startedAt,
        Instant heartbeatAt,
        Long timeoutSeconds,
        Long heartbeatSeconds,
        Instant timeoutAt,
        Instant heartbeatDeadlineAt,
        Instant completedAt,
        Long durationMs,
        String error,
        String cause,
        int dispatchAttemptCount,
        String lastDispatchError,
        Instant createdAt,
        Instant updatedAt
) {
}
