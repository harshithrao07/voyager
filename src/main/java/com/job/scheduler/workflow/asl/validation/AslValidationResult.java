package com.job.scheduler.workflow.asl.validation;

import java.util.List;

public record AslValidationResult(List<AslValidationIssue> issues) {
    public AslValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean isValid() {
        return isLanguageValid() && isDialectValid();
    }

    public boolean isLanguageValid() {
        return hasNoIssues(AslValidationCategory.ASL);
    }

    public boolean isDialectValid() {
        return hasNoIssues(AslValidationCategory.DIALECT);
    }

    public boolean isRuntimeSupported() {
        return hasNoIssues(AslValidationCategory.RUNTIME_SUPPORT);
    }

    public boolean isExecutable() {
        return isLanguageValid() && isDialectValid() && isRuntimeSupported();
    }

    private boolean hasNoIssues(AslValidationCategory category) {
        return issues.stream()
                .noneMatch(issue -> issue.category() == category);
    }
}
