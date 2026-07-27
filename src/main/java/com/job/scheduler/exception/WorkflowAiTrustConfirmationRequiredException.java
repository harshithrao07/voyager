package com.job.scheduler.exception;

import com.job.scheduler.dto.ElevatedMcpToolDTO;

import java.util.List;

/**
 * Thrown when saving an AI-authored workflow that wires in {@code WRITE}/{@code DESTRUCTIVE}
 * MCP tools without explicit trust confirmation. Carries the offending tools so the API can
 * present them for the user to review, then retry with confirmation.
 */
public class WorkflowAiTrustConfirmationRequiredException extends RuntimeException {

    private final transient List<ElevatedMcpToolDTO> elevatedTools;

    public WorkflowAiTrustConfirmationRequiredException(List<ElevatedMcpToolDTO> elevatedTools) {
        super("This workflow calls MCP tools that can write or delete data. "
                + "Confirm the elevated-trust tools before creating it.");
        this.elevatedTools = List.copyOf(elevatedTools);
    }

    public List<ElevatedMcpToolDTO> getElevatedTools() {
        return elevatedTools;
    }
}
