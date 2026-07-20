package com.job.scheduler.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * The user's approval of some or all functions the model proposed. Carries the (possibly edited)
 * function specs so user corrections in the review card are what actually gets created.
 */
public record WorkflowAiProvisionRequestDTO(
        @NotNull
        UUID conversationId,

        List<WorkflowAiProposedFunctionDTO> functions,

        UUID modelConfigId
) {
}
