package com.job.scheduler.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One environment variable an MCP server needs, surfaced so the register form can
 * prefill it. Secret values are never carried from the registry — {@code secret}
 * only tells the UI to render a blank, masked field the user fills in.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicMcpEnvVarDTO(
        String name,
        String description,
        boolean secret,
        boolean required,
        String defaultValue
) {
}
