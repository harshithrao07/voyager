package com.job.scheduler.workflow.asl.validation;

import tools.jackson.databind.JsonNode;

public interface AslDefinitionValidator {
    AslValidationResult validate(JsonNode definition);
}
