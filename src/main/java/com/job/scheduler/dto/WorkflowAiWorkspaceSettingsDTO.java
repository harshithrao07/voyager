package com.job.scheduler.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record WorkflowAiWorkspaceSettingsDTO(
        String name,
        String cronExpression,
        @PositiveOrZero(message = "Max attempts cannot be negative") Integer maxAttempts,
        String idempotencyKey,
        String timezone
) {
}
