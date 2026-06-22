package com.job.scheduler.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record WorkflowDefinitionResponseDTO(
        UUID id,
        long revision,
        String definitionHash,
        JsonNode definition,
        boolean active,
        Instant createdAt
) {
}
