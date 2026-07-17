package com.job.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record WorkflowAiChatRequestDTO(
        @NotNull(message = "Conversation id cannot be null") UUID conversationId,
        @NotBlank(message = "Message cannot be empty") String message,
        UUID modelConfigId,
        JsonNode definition
) {
}
