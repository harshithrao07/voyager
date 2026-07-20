package com.job.scheduler.enums;

public enum McpAuthType {
    /** No authentication. */
    NONE,
    /** {@code Authorization: Bearer <token>} (HTTP), or token injected into an env var (STDIO). */
    BEARER_TOKEN,
    /** A custom header {@code <authHeaderName>: <token>} (HTTP only). */
    API_KEY,
    /** {@code Authorization: Basic base64(authUsername:<token>)} (HTTP only). */
    BASIC,
    /** One or more custom {@code <name>: <secret>} request headers (HTTP only). */
    CUSTOM_HEADERS
}
