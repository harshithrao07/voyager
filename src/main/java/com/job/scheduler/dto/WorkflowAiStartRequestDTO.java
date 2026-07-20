package com.job.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record WorkflowAiStartRequestDTO(
        @NotBlank(message = "Instruction cannot be empty") String instruction,
        UUID modelConfigId,
        String userDateTime,
        JsonNode definition,
        /** Raw editor buffer, sent even when incomplete/invalid so the AI can see in-progress edits. */
        String definitionText
) {
}
