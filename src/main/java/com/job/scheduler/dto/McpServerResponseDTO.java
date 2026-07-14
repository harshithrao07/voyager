package com.job.scheduler.dto;

import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record McpServerResponseDTO(
        UUID id,
        String serverId,
        String displayName,
        String baseUrl,
        String endpoint,
        String command,
        List<String> args,
        Map<String, String> env,
        String authEnvVar,
        McpTransport transport,
        McpAuthType authType,
        String authTokenRef,
        McpTrustLevel trustLevel,
        McpServerStatus status,
        Integer requestTimeoutMs,
        Instant createdAt,
        Instant updatedAt
) {
}
