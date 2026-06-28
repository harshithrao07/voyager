package com.job.scheduler.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record WorkflowAiReviewAslRequestDTO(
        @NotNull(message = "Conversation id cannot be null") UUID conversationId,
        @NotNull(message = "ASL definition cannot be null") JsonNode definition
) {
}
