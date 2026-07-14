package com.job.scheduler.exception;

/**
 * A registered MCP server could not be reached or started — e.g. an STDIO
 * command that does not exist in the backend's environment, or an HTTP server
 * that is unreachable. Carries a human-readable, actionable message that is
 * surfaced to the caller as a 502.
 */
public class McpConnectionException extends RuntimeException {
    public McpConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
