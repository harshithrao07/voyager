package com.job.scheduler.dto;

import com.job.scheduler.enums.WorkflowAiConversationStage;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowAiConversationDetailDTO(
        UUID id,
        String name,
        WorkflowAiConversationStage stage,
        UUID modelConfigId,
        String modelDisplayName,
        String initialInstruction,
        JsonNode aslDefinition,
        JsonNode finalPlan,
        CreateWorkflowRequestDTO draftWorkflowPayload,
        List<WorkflowAiMessageDTO> messages,
        Instant createdAt,
        Instant updatedAt
) {
}
