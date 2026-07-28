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
        WorkflowAiResourcePlanDTO resourcePlan,
        UUID resourcePlanMessageId,
        UUID workflowId,
        WorkflowResponseDTO workflow,
        WorkflowAiMessageDTO assistantMessage,
        /**
         * The raw, cleaned model reply for this turn (bounded). Carries the model's exact output —
         * including an ASL that was rejected in validation — so diagnostics such as the evaluation
         * harness can show what the model produced. Null for turns that do not capture it.
         */
        String rawAssistantReply
) {
}
