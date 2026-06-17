package com.job.scheduler.dto;

import com.job.scheduler.enums.McpToolExecutionStatus;
import com.job.scheduler.enums.McpTrustLevel;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record McpToolExecutionResponseDTO(
        UUID id,
        UUID jobId,
        UUID executionLogId,
        UUID stepExecutionId,
        String serverId,
        String toolName,
        JsonNode arguments,
        JsonNode result,
        McpToolExecutionStatus status,
        McpTrustLevel maxAllowedTrustLevel,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        Instant createdAt,
        Instant updatedAt
) {
}
