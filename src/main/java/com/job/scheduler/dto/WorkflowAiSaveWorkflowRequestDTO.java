package com.job.scheduler.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record WorkflowAiSaveWorkflowRequestDTO(
        @NotNull(message = "Workflow cannot be null")
        @Valid CreateWorkflowRequestDTO workflow,
        @NotNull(message = "Canvas layout cannot be null") JsonNode canvasLayout,
        /** User acknowledged the WRITE/DESTRUCTIVE MCP tools this workflow calls. */
        Boolean confirmElevatedTrust
) {
}
