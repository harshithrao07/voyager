package com.job.scheduler.workflow.asl.validation;

import tools.jackson.databind.JsonNode;

import java.util.List;

@FunctionalInterface
interface NestedMachineValidator {
    void validate(
            JsonNode definition,
            String location,
            boolean itemProcessor,
            List<AslValidationIssue> issues
    );
}
