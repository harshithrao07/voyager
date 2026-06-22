package com.job.scheduler.dto;

import tools.jackson.databind.JsonNode;

public record StepResult(JsonNode output) {
    public static StepResult empty() {
        return new StepResult(null);
    }
}
