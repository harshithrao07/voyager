package com.job.scheduler.dto;

import tools.jackson.databind.JsonNode;

public record StartWorkflowExecutionRequestDTO(JsonNode input) {
}
