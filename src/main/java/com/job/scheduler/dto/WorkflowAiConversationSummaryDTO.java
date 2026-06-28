package com.job.scheduler.dto;

import com.job.scheduler.enums.WorkflowAiConversationStage;

import java.time.Instant;
import java.util.UUID;

public record WorkflowAiConversationSummaryDTO(
        UUID id,
        String name,
        WorkflowAiConversationStage stage,
        UUID modelConfigId,
        String modelDisplayName,
        String initialInstruction,
        Instant createdAt,
        Instant updatedAt
) {
}
