package com.job.scheduler.workflow.task;

import tools.jackson.databind.JsonNode;

/**
 * A Task resource failure classified to a stable ASL error name. The
 * {@link #error()} is what a workflow author matches in
 * {@code Retry}/{@code Catch.ErrorEquals}; the optional {@link #detail()} is
 * structured failure information (e.g. an HTTP status and response body) that is
 * surfaced to {@code $states.errorOutput.Cause}.
 */
public class TaskResourceException extends RuntimeException {
    private final String error;
    private final transient JsonNode detail;

    public TaskResourceException(String error, String message) {
        super(message);
        this.error = error;
        this.detail = null;
    }

    public TaskResourceException(String error, String message, JsonNode detail) {
        super(message);
        this.error = error;
        this.detail = detail;
    }

    public TaskResourceException(
            String error,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.error = error;
        this.detail = null;
    }

    /** The stable ASL error name (see {@link TaskResourceErrors}). */
    public String error() {
        return error;
    }

    /** Structured failure detail merged into the cause, or null. */
    public JsonNode detail() {
        return detail;
    }
}
