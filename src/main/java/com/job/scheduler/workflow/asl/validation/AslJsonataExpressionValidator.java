package com.job.scheduler.workflow.asl.validation;

import com.api.jsonata4java.expressions.Expressions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class AslJsonataExpressionValidator {
    private static final Pattern STATES_INTRINSIC =
            Pattern.compile("States\\.[A-Za-z][A-Za-z0-9_]*\\s*\\(");

    public boolean isExpression(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("{%") && trimmed.endsWith("%}");
    }

    public void validateRequired(
            String value,
            String location,
            boolean allowResult,
            boolean allowErrorOutput,
            List<AslValidationIssue> issues
    ) {
        if (!isExpression(value)) {
            issues.add(issue(
                    location,
                    "JSONATA_EXPRESSION_REQUIRED",
                    "Field must be a JSONata expression delimited by {% and %}"
            ));
            return;
        }
        validate(value, location, allowResult, allowErrorOutput, issues);
    }

    public void validate(
            String value,
            String location,
            boolean allowResult,
            boolean allowErrorOutput,
            List<AslValidationIssue> issues
    ) {
        String trimmed = value.trim();
        boolean startsExpression = trimmed.startsWith("{%");
        boolean endsExpression = trimmed.endsWith("%}");
        if (startsExpression != endsExpression) {
            issues.add(issue(
                    location,
                    "INVALID_JSONATA_DELIMITERS",
                    "JSONata expressions must be delimited by {% and %}"
            ));
            return;
        }
        if (!startsExpression) {
            return;
        }

        String expression = trimmed.substring(2, trimmed.length() - 2).trim();
        if (expression.isEmpty()) {
            issues.add(issue(
                    location,
                    "EMPTY_JSONATA_EXPRESSION",
                    "JSONata expression must not be empty"
            ));
            return;
        }

        if (!allowResult && expression.contains("$states.result")) {
            issues.add(issue(
                    location,
                    "STATES_RESULT_NOT_AVAILABLE",
                    "$states.result is not available in this field"
            ));
        }
        if (!allowErrorOutput && expression.contains("$states.errorOutput")) {
            issues.add(issue(
                    location,
                    "STATES_ERROR_OUTPUT_NOT_AVAILABLE",
                    "$states.errorOutput is available only inside a matching Catcher"
            ));
        }
        if (STATES_INTRINSIC.matcher(expression).find()) {
            issues.add(issue(
                    location,
                    "STATES_INTRINSIC_NOT_ALLOWED",
                    "States.* intrinsic functions are not allowed in the JSONata dialect"
            ));
            return;
        }

        try {
            Expressions.parse(expression);
        } catch (Exception exception) {
            issues.add(issue(
                    location,
                    "INVALID_JSONATA_SYNTAX",
                    "JSONata expression could not be parsed: " + parserMessage(exception)
            ));
        }
    }

    private String parserMessage(Exception exception) {
        String message = exception.getLocalizedMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private AslValidationIssue issue(String location, String code, String message) {
        return new AslValidationIssue(
                location,
                AslValidationCategory.DIALECT,
                code,
                message
        );
    }
}
