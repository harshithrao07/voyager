package com.job.scheduler.dto;

import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowExecutionScopeDTO(
        UUID id,
        UUID parentScopeId,
        ExecutionScopeType scopeType,
        String scopePath,
        String ownerStateName,
        Integer branchIndex,
        Long itemIndex,
        ExecutionScopeStatus status,
        String currentStateName,
        JsonNode currentStateInput,
        JsonNode variables,
        JsonNode output,
        Instant wakeAt,
        String error,
        String cause,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        List<WorkflowStateExecutionDTO> stateExecutions
) {
}
