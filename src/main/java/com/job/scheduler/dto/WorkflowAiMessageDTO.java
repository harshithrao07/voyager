package com.job.scheduler.dto;

import com.job.scheduler.enums.WorkflowAiMessageRole;

import java.time.Instant;
import java.util.UUID;

public record WorkflowAiMessageDTO(
        UUID id,
        WorkflowAiMessageRole role,
        String content,
        UUID modelConfigId,
        String modelDisplayName,
        Long durationMs,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        String thinkingContent,
        String finishReason,
        UUID regeneratedFromMessageId,
        WorkflowAiResourcePlanDTO resourcePlan,
        Instant createdAt
) {
}
