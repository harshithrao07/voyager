package com.job.scheduler.dto;

import com.job.scheduler.enums.JobPriority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record WorkflowJobRequestDTO(
        @NotNull(message = "Job Priority cannot be null") JobPriority jobPriority,
        String cronExpression,
        @PositiveOrZero(message = "Max attempts cannot be negative") Integer maxAttempts,
        @NotBlank(message = "Idempotency Key cannot be blank") String idempotencyKey,
        @Valid @NotEmpty(message = "Workflow must contain at least one step") List<JobStepRequestDTO> steps
) {
}
