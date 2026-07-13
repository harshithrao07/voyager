package com.job.scheduler.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record DraftStateTestResponseDTO(
        String status,
        String stateName,
        String stateType,
        JsonNode input,
        JsonNode output,
        JsonNode variables,
        String nextStateName,
        String taskResource,
        JsonNode taskArguments,
        Instant wakeAt,
        String error,
        String cause,
        long durationMs
) {
}
