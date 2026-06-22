package com.job.scheduler.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record CreateWorkflowRevisionRequestDTO(
        @NotNull(message = "ASL definition cannot be null") JsonNode definition,
        boolean activate
) {
}
