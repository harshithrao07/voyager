package com.job.scheduler.dto;

public record WorkflowAiSaveWorkflowResponseDTO(
        WorkflowResponseDTO workflow,
        WorkflowDefinitionResponseDTO revision
) {
}
