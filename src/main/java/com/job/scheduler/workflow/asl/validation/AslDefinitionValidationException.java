package com.job.scheduler.workflow.asl.validation;

import java.util.List;

public class AslDefinitionValidationException extends IllegalArgumentException {
    private final List<AslValidationIssue> issues;

    public AslDefinitionValidationException(List<AslValidationIssue> issues) {
        super("ASL definition validation failed");
        this.issues = List.copyOf(issues);
    }

    public List<AslValidationIssue> getIssues() {
        return issues;
    }
}
