package com.job.scheduler.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record WorkflowAiWorkspaceRequestDTO(
        // `definition` keeps older API clients compatible. New clients send definitionText so
        // temporarily incomplete JSON can be restored after a refresh.
        JsonNode definition,
        String definitionText,
        @NotNull(message = "Canvas layout cannot be null") JsonNode canvasLayout,
        @NotNull(message = "Workspace settings cannot be null")
        @Valid WorkflowAiWorkspaceSettingsDTO settings
) {
    @AssertTrue(message = "Definition or definition text is required")
    public boolean isDefinitionPresent() {
        return definition != null || definitionText != null;
    }
}
