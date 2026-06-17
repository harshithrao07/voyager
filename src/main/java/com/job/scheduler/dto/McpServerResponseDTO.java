package com.job.scheduler.dto;

import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;

import java.time.Instant;
import java.util.UUID;

public record McpServerResponseDTO(
        UUID id,
        String serverId,
        String displayName,
        String baseUrl,
        String endpoint,
        McpTransport transport,
        McpAuthType authType,
        String authTokenRef,
        McpTrustLevel trustLevel,
        McpServerStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
