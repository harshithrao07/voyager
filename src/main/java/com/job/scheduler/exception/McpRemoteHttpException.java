package com.job.scheduler.exception;

/**
 * An HTTP MCP server rejected a transport request with a concrete HTTP status.
 * The status is preserved so callers see the remote failure instead of a generic 500.
 */
public class McpRemoteHttpException extends RuntimeException {
    private final int statusCode;

    public McpRemoteHttpException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
