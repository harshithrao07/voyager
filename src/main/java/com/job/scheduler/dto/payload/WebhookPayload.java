package com.job.scheduler.dto.payload;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public record WebhookPayload(
        @NotBlank(message = "URL is required")
        String url,

        @Pattern(
                regexp = "(?i)GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS",
                message = "must be GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS"
        )
        String method,

        Map<@NotBlank @Pattern(
                regexp = "[!#$%&'*+.^_`|~0-9A-Za-z-]+",
                message = "must be a valid HTTP header name"
        ) String, @NotBlank @Pattern(
                regexp = "[^\\r\\n]*",
                message = "must not contain line breaks"
        ) String> headers,

        JsonNode body,

        Boolean includeExecutionContextHeaders
) {
    /** Preserves the original POST-only payload contract for existing callers. */
    public WebhookPayload(String url, JsonNode body) {
        this(url, null, null, body, null);
    }

    public WebhookPayload(
            String url,
            String method,
            Map<String, String> headers,
            JsonNode body
    ) {
        this(url, method, headers, body, null);
    }
}
