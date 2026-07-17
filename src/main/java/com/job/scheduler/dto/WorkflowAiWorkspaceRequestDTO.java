package com.job.scheduler.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record WorkflowAiWorkspaceRequestDTO(
        @NotNull(message = "ASL definition cannot be null") JsonNode definition,
        @NotNull(message = "Canvas layout cannot be null") JsonNode canvasLayout,
        @NotNull(message = "Workspace settings cannot be null")
        @Valid WorkflowAiWorkspaceSettingsDTO settings
) {
}
