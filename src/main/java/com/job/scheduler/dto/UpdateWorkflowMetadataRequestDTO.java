package com.job.scheduler.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.JsonNode;

public record UpdateWorkflowMetadataRequestDTO(
        @NotNull(message = "Expected version cannot be null")
        @PositiveOrZero(message = "Expected version cannot be negative")
        Long expectedVersion,
        String name,
        JsonNode cronExpression,
        JsonNode scheduledInput,
        String timezone,
        @PositiveOrZero(message = "Max attempts cannot be negative")
        Integer maxAttempts
) {
    public UpdateWorkflowMetadataRequestDTO(
            Long expectedVersion,
            String name,
            JsonNode cronExpression,
            String timezone,
            Integer maxAttempts
    ) {
        this(expectedVersion, name, cronExpression, null, timezone, maxAttempts);
    }
}
