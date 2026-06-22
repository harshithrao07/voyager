package com.job.scheduler.workflow.asl.validation;

public record AslValidationIssue(
        String location,
        AslValidationCategory category,
        String code,
        String message
) {
}
