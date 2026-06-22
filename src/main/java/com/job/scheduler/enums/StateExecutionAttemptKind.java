package com.job.scheduler.enums;

/**
 * Distinguishes what a {@link com.job.scheduler.entity.StateExecutionAttempt}
 * executes. Most attempts are TASK (a Task state's resource). A Map state's fork
 * StateExecution can also carry READER and WRITER attempts for its ItemReader
 * and ResultWriter resources, which run through the same dispatch/worker path but
 * are completed by Map-specific logic.
 */
public enum StateExecutionAttemptKind {
    TASK,
    READER,
    WRITER
}
