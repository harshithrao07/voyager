package com.job.scheduler.dto;

import com.job.scheduler.enums.WorkflowExecutionStatus;

import java.time.Instant;
import java.util.UUID;

public record WorkflowExecutionCancellationResponseDTO(
        UUID workflowExecutionId,
        WorkflowExecutionStatus status,
        String error,
        String cause,
        Instant completedAt
) {
}
