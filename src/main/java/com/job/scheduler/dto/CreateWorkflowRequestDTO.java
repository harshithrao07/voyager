package com.job.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.JsonNode;

public record CreateWorkflowRequestDTO(
        @NotBlank(message = "Workflow name cannot be blank") String name,
        String cronExpression,
        @PositiveOrZero(message = "Max attempts cannot be negative") Integer maxAttempts,
        @NotBlank(message = "Idempotency key cannot be blank") String idempotencyKey,
        String timezone,
        @NotNull(message = "ASL definition cannot be null") JsonNode definition
) {
}
