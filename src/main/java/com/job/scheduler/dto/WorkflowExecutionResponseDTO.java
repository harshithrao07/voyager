package com.job.scheduler.dto;

import com.job.scheduler.enums.WorkflowExecutionStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record WorkflowExecutionResponseDTO(
        UUID workflowExecutionId,
        WorkflowExecutionStatus status,
        JsonNode output,
        String error,
        String cause,
        Instant wakeAt,
        UUID stateExecutionAttemptId
) {
}
