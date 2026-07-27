package com.job.scheduler.dto;

import java.util.List;

/**
 * The result of scanning a workflow definition for MCP calls that grant elevated trust.
 * When {@link #requiresConfirmation()} is true, the user must explicitly confirm the
 * listed {@link #tools()} before the workflow is created or activated.
 */
public record WorkflowAiTrustReviewDTO(
        boolean requiresConfirmation,
        List<ElevatedMcpToolDTO> tools
) {
    public static WorkflowAiTrustReviewDTO none() {
        return new WorkflowAiTrustReviewDTO(false, List.of());
    }
}
