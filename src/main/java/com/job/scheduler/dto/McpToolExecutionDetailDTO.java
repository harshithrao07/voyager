package com.job.scheduler.dto;

import com.job.scheduler.enums.McpToolExecutionStatus;
import com.job.scheduler.enums.McpTrustLevel;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record McpToolExecutionDetailDTO(
        String kind,
        UUID id,
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
    public McpToolExecutionDetailDTO(
            UUID id,
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
        this(
                "MCP_TOOL",
                id,
                serverId,
                toolName,
                arguments,
                result,
                status,
                maxAllowedTrustLevel,
                errorMessage,
                startedAt,
                completedAt,
                durationMs,
                createdAt,
                updatedAt
        );
    }
}
