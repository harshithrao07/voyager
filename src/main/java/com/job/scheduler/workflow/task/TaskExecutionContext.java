package com.job.scheduler.workflow.task;

import java.util.UUID;

/**
 * Ambient workflow-run context handed to a {@link TaskResource} so it can link
 * its side effects back to the execution and state that triggered them. Empty
 * ({@link #NONE}) for out-of-band calls such as function test invocations.
 */
public record TaskExecutionContext(
        UUID workflowExecutionId,
        String stateName,
        UUID stateExecutionAttemptId
) {
    public static final TaskExecutionContext NONE =
            new TaskExecutionContext(null, null, null);

    public TaskExecutionContext(UUID workflowExecutionId, String stateName) {
        this(workflowExecutionId, stateName, null);
    }
}
