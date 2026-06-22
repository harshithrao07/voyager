package com.job.scheduler.dto;

import com.job.scheduler.enums.AslStateType;
import com.job.scheduler.enums.StateExecutionStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowStateExecutionDTO(
        UUID id,
        long sequenceNumber,
        String stateName,
        AslStateType stateType,
        StateExecutionStatus status,
        String resource,
        JsonNode input,
        JsonNode output,
        Instant retryAt,
        String error,
        String cause,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        List<WorkflowStateExecutionAttemptDTO> attempts
) {
}
