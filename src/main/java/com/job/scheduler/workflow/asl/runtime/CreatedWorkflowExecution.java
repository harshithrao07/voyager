package com.job.scheduler.workflow.asl.runtime;

import java.util.UUID;

public record CreatedWorkflowExecution(
        UUID workflowExecutionId,
        UUID rootScopeId
) {
}
