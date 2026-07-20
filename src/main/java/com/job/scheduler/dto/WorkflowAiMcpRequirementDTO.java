package com.job.scheduler.dto;

/**
 * A capability the workflow needs from an MCP tool that no enabled server currently provides.
 * Voyager cannot create MCP servers (they are external endpoints with their own trust and
 * credentials), so this is a recommendation: the user registers and syncs a server that provides
 * the capability, then continues so the model can match a real discovered tool by description.
 */
public record WorkflowAiMcpRequirementDTO(
        String capability,
        String suggestedToolName,
        String reason,
        String trustLevelHint
) {
}
