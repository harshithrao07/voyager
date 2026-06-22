package com.job.scheduler.dto;

import com.job.scheduler.enums.WorkflowPriority;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.JsonNode;

public record UpdateWorkflowMetadataRequestDTO(
        @NotNull(message = "Expected version cannot be null")
        @PositiveOrZero(message = "Expected version cannot be negative")
        Long expectedVersion,
        String name,
        WorkflowPriority priority,
        JsonNode cronExpression,
        String timezone,
        @PositiveOrZero(message = "Max attempts cannot be negative")
        Integer maxAttempts
) {
}
