package com.job.scheduler.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record McpToolResponseDTO(
        UUID id,
        String serverId,
        String toolName,
        String title,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        boolean enabled,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt
) {
}
