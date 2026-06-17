package com.job.scheduler.dto;

import com.job.scheduler.enums.JobType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record JobStepResponseDTO(
        UUID id,
        int stepOrder,
        JobType stepType,
        JsonNode payload,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
