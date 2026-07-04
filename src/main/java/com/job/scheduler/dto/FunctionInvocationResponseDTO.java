package com.job.scheduler.dto;

import com.job.scheduler.enums.FunctionInvocationStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record FunctionInvocationResponseDTO(
        UUID id,
        UUID functionId,
        int version,
        UUID workflowExecutionId,
        String stateName,
        String judge0Token,
        FunctionInvocationStatus status,
        JsonNode input,
        JsonNode output,
        String stdout,
        String stderr,
        String compileOutput,
        String message,
        Integer exitCode,
        Integer exitSignal,
        Integer judge0StatusId,
        String judge0StatusDescription,
        String errorName,
        String errorMessage,
        Double timeSeconds,
        Double wallTimeSeconds,
        Long memoryKb,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        Instant createdAt,
        Instant updatedAt
) {
}
