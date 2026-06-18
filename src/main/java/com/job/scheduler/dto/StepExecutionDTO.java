package com.job.scheduler.dto;

import com.job.scheduler.enums.JobStatus;
import com.job.scheduler.enums.JobType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record StepExecutionDTO(
        UUID id,
        UUID jobStepId,
        int stepOrder,
        JobType stepType,
        JobStatus executionStatus,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String errorMessage,
        JsonNode resolvedInput,
        JsonNode inputRef,
        JsonNode output,
        JsonNode outputRef,
        Instant createdAt,
        Object details
) {
}
