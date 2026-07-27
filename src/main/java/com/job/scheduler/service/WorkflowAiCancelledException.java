package com.job.scheduler.service;

/**
 * Raised when a workflow-AI turn is cancelled by the client mid-flight (its websocket went away).
 *
 * <p>It propagates out of the {@code @Transactional} turn method so the appended user and assistant
 * messages roll back and never reach the database — a cancelled turn leaves no trace to reappear on
 * a later reload.
 */
public class WorkflowAiCancelledException extends RuntimeException {
    public WorkflowAiCancelledException() {
        super("The workflow AI turn was cancelled.");
    }
}
