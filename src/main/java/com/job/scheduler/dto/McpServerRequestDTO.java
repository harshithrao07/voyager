package com.job.scheduler.dto;

import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Map;

public record McpServerRequestDTO(
        @NotBlank
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$", message = "must use lowercase letters, numbers, and hyphens")
        String serverId,

        @NotBlank
        String displayName,

        // HTTP transport fields (required for HTTP, validated in the service).
        String baseUrl,

        @Pattern(regexp = "^/.*", message = "must start with /")
        String endpoint,

        // STDIO transport fields (command required for STDIO, validated in the service).
        String command,

        List<String> args,

        Map<String, String> env,

        // STDIO secret env vars (name -> plaintext value); encrypted on save, never returned.
        Map<String, String> secretEnv,

        // CUSTOM_HEADERS auth: request headers (name -> plaintext value); encrypted on save, never returned.
        Map<String, String> secretHeaders,

        String authEnvVar,

        @NotNull
        McpTransport transport,

        @NotNull
        McpAuthType authType,

        // Plaintext auth token/secret; encrypted on save, never returned.
        String authToken,

        String authHeaderName,

        String authUsername,

        McpTrustLevel trustLevel,

        McpServerStatus status,

        @Positive(message = "requestTimeoutMs must be positive")
        Integer requestTimeoutMs
) {
}
