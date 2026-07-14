package com.job.scheduler.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record UpdateWorkflowCanvasLayoutRequestDTO(
        @NotNull(message = "Canvas positions cannot be null") JsonNode positions
) {
}
