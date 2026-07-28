package com.job.scheduler.dto;

import com.job.scheduler.enums.AiModelEvaluationMode;
import com.job.scheduler.enums.AiModelEvaluationStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record AiModelEvaluationDTO(
        UUID runId,
        UUID modelConfigId,
        String modelDisplayName,
        AiModelEvaluationStatus status,
        AiModelEvaluationMode mode,
        int repetitions,
        int completedCases,
        int totalCases,
        boolean cancelRequested,
        boolean stale,
        JsonNode result,
        JsonNode progressObservations,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
}
