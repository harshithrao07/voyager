package com.job.scheduler.dto;

import jakarta.validation.constraints.Min;
import tools.jackson.databind.JsonNode;

public record FunctionTestInvocationRequestDTO(
        @Min(1)
        Integer version,

        JsonNode input
) {
}
