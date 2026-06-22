package com.job.scheduler.dto;

import java.util.UUID;

public record WorkflowTaskDispatchEvent(UUID stateExecutionAttemptId) {
}
