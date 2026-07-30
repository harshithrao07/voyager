package com.job.scheduler.dto;

import com.job.scheduler.enums.WorkflowStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record WorkflowResponseDTO(
        UUID id,
        long version,
        String name,
        WorkflowStatus status,
        String cronExpression,
        String timezone,
        Instant nextRunAt,
        JsonNode scheduledInput,
        int maxAttempts,
        String idempotencyKey,
        WorkflowDefinitionResponseDTO activeDefinition,
        Instant createdAt,
        Instant updatedAt
) {
    public WorkflowResponseDTO(
            UUID id,
            long version,
            String name,
            WorkflowStatus status,
            String cronExpression,
            String timezone,
            Instant nextRunAt,
            int maxAttempts,
            String idempotencyKey,
            WorkflowDefinitionResponseDTO activeDefinition,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                version,
                name,
                status,
                cronExpression,
                timezone,
                nextRunAt,
                null,
                maxAttempts,
                idempotencyKey,
                activeDefinition,
                createdAt,
                updatedAt
        );
    }
}
