package com.job.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record DraftStateTestRequestDTO(
        @NotNull(message = "ASL definition cannot be null") JsonNode definition,
        @NotBlank(message = "State name cannot be blank") String stateName,
        JsonNode input,
        JsonNode variables,
        Boolean executeTask
) {
}
