package com.job.scheduler.dto;

import com.job.scheduler.enums.AiModelEvaluationMode;
import jakarta.validation.constraints.NotNull;

public record AiModelEvaluationStartRequestDTO(
        @NotNull AiModelEvaluationMode mode
) {
}
