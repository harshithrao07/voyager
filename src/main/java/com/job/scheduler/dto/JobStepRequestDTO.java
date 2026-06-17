package com.job.scheduler.dto;

import com.job.scheduler.enums.JobType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.JsonNode;

public record JobStepRequestDTO(
        @Positive(message = "Step order must be positive") int stepOrder,
        @NotNull(message = "Step Type cannot be null") JobType stepType,
        @NotNull(message = "Step payload cannot be null") JsonNode payload
) {
}
