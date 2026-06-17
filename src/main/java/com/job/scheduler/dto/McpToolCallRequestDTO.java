package com.job.scheduler.dto;

import com.job.scheduler.enums.McpTrustLevel;

import java.util.Map;

public record McpToolCallRequestDTO(
        Map<String, Object> arguments,
        McpTrustLevel maxAllowedTrustLevel
) {
}
