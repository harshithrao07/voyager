package com.job.scheduler.dto;

import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record McpServerRequestDTO(
        @NotBlank
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$", message = "must use lowercase letters, numbers, and hyphens")
        String serverId,

        @NotBlank
        String displayName,

        @NotBlank
        String baseUrl,

        @NotBlank
        @Pattern(regexp = "^/.*", message = "must start with /")
        String endpoint,

        @NotNull
        McpTransport transport,

        @NotNull
        McpAuthType authType,

        String authTokenRef,

        McpTrustLevel trustLevel,

        McpServerStatus status,

        @Positive(message = "requestTimeoutMs must be positive")
        Integer requestTimeoutMs
) {
}
