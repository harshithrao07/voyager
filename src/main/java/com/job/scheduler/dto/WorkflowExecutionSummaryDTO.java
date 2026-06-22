package com.job.scheduler.dto;

import com.job.scheduler.enums.WorkflowExecutionStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record WorkflowExecutionSummaryDTO(
        UUID id,
        UUID workflowId,
        UUID workflowDefinitionId,
        long definitionRevision,
        long runNumber,
        WorkflowExecutionStatus status,
        Instant scheduledFor,
        JsonNode input,
        JsonNode output,
        String error,
        String cause,
        Instant deadlineAt,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
