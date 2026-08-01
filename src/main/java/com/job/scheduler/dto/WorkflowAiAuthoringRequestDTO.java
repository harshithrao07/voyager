package com.job.scheduler.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record WorkflowAiAuthoringRequestDTO(
        @NotNull(message = "ASL definition cannot be null") JsonNode definition,
        UUID modelConfigId
) {
}
