package com.job.scheduler.dto.payload;

import com.job.scheduler.enums.McpTrustLevel;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

public record McpToolPayload(
        @NotBlank(message = "MCP server ID is required")
        String serverId,

        @NotBlank(message = "MCP tool name is required")
        String toolName,

        JsonNode arguments,

        McpTrustLevel maxAllowedTrustLevel
) {
}
