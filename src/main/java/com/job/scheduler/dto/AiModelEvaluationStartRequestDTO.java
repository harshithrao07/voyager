package com.job.scheduler.dto;

import com.job.scheduler.enums.AiModelEvaluationMode;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Starts a benchmark run. {@code judgeModelConfigId} optionally names a second registered model
 * that grades every case qualitatively (LLM-as-judge); null keeps the run deterministic-only.
 */
public record AiModelEvaluationStartRequestDTO(
        @NotNull AiModelEvaluationMode mode,
        UUID judgeModelConfigId
) {
}
