package com.job.scheduler.dto;

import java.util.List;

/**
 * Resources the workflow model needs but that do not yet exist in the catalog: functions Voyager
 * can scaffold on approval, and MCP capabilities the user must attach a server for. Surfaced to the
 * user for review before any workflow ASL is generated.
 */
public record WorkflowAiResourcePlanDTO(
        List<WorkflowAiProposedFunctionDTO> functions,
        List<WorkflowAiMcpRequirementDTO> mcpRequirements
) {
    public boolean isEmpty() {
        return (functions == null || functions.isEmpty())
                && (mcpRequirements == null || mcpRequirements.isEmpty());
    }
}
