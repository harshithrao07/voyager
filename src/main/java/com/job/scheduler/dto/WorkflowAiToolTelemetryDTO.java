package com.job.scheduler.dto;

import java.util.List;

/** Persisted observability for one workflow-AI turn's bounded tool loop. */
public record WorkflowAiToolTelemetryDTO(
        boolean toolLoopUsed,
        int modelCalls,
        int toolModelCalls,
        int nativeToolCalls,
        int automaticToolCalls,
        int rejectedFinals,
        int totalInputTokens,
        int totalOutputTokens,
        int totalTokens,
        int promptCatalogTokensPerCall,
        int toolSchemaTokensPerCall,
        int estimatedNetInputTokensSaved,
        String fallbackReason,
        List<ToolCall> calls
) {
    public record ToolCall(
            String name,
            String mode,
            String status,
            long durationMs,
            int resultCount
    ) {
    }
}
