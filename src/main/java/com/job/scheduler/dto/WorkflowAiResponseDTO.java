package com.job.scheduler.dto;

import com.job.scheduler.enums.WorkflowAiConversationStage;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public record WorkflowAiResponseDTO(
        UUID conversationId,
        String conversationName,
        WorkflowAiConversationStage stage,
        String message,
        JsonNode aslDefinition,
        List<String> validationIssues,
        JsonNode finalPlan,
        CreateWorkflowRequestDTO draftWorkflowPayload,
        UUID workflowId,
        WorkflowResponseDTO workflow
) {
}
