package com.job.scheduler.dto;

import java.time.Instant;
import java.util.List;

public record McpToolSyncResultDTO(
        String serverId,
        int discoveredCount,
        int createdCount,
        int updatedCount,
        int disabledCount,
        Instant syncedAt,
        List<McpToolResponseDTO> tools
) {
}
