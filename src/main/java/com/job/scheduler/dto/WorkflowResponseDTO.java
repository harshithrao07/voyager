package com.job.scheduler.dto;

import com.job.scheduler.enums.WorkflowPriority;
import com.job.scheduler.enums.WorkflowStatus;

import java.time.Instant;
import java.util.UUID;

public record WorkflowResponseDTO(
        UUID id,
        long version,
        String name,
        WorkflowStatus status,
        WorkflowPriority priority,
        String cronExpression,
        String timezone,
        Instant nextRunAt,
        int maxAttempts,
        String idempotencyKey,
        WorkflowDefinitionResponseDTO activeDefinition,
        Instant createdAt,
        Instant updatedAt
) {
}
