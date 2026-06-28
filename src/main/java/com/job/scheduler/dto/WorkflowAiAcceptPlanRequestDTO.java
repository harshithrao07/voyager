package com.job.scheduler.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record WorkflowAiAcceptPlanRequestDTO(
        @NotNull(message = "Conversation id cannot be null") UUID conversationId
) {
}
