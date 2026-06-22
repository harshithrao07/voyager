package com.job.scheduler.workflow.asl.validation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AslJsonataExpressionValidatorTest {
    private final AslJsonataExpressionValidator validator =
            new AslJsonataExpressionValidator();

    @Test
    void acceptsValidJsonataSyntax() {
        List<AslValidationIssue> issues = validate(
                "{% $states.input.total > 1000 and $count($states.input.items) > 0 %}",
                false,
                false
        );

        assertThat(issues).isEmpty();
    }

    @Test
    void acceptsValidObjectConstruction() {
        List<AslValidationIssue> issues = validate(
                "{% {'customer': $states.input.customerId, 'accepted': true} %}",
                false,
                false
        );

        assertThat(issues).isEmpty();
    }

    @Test
    void rejectsMalformedJsonataSyntax() {
        assertCode(
                validate("{% $states.input.total > %}", false, false),
                "INVALID_JSONATA_SYNTAX"
        );
    }

    @Test
    void rejectsMalformedFunctionCall() {
        assertCode(
                validate("{% $count( %}", false, false),
                "INVALID_JSONATA_SYNTAX"
        );
    }

    @Test
    void rejectsEmptyExpressionBeforeParsing() {
        assertCode(
                validate("{%   %}", false, false),
                "EMPTY_JSONATA_EXPRESSION"
        );
    }

    @Test
    void ignoresStaticStringsInJsonataCapableFields() {
        assertThat(validate("plain text", false, false)).isEmpty();
    }

    @Test
    void requiredExpressionRejectsStaticString() {
        List<AslValidationIssue> issues = new ArrayList<>();

        validator.validateRequired(
                "true",
                "$.Condition",
                false,
                false,
                issues
        );

        assertCode(issues, "JSONATA_EXPRESSION_REQUIRED");
    }

    @Test
    void enforcesResultContextBeforeParsing() {
        assertCode(
                validate("{% $states.result.ok %}", false, false),
                "STATES_RESULT_NOT_AVAILABLE"
        );
    }

    @Test
    void allowsResultWhenContextProvidesIt() {
        assertThat(validate("{% $states.result.ok %}", true, false)).isEmpty();
    }

    @Test
    void enforcesErrorOutputContext() {
        assertCode(
                validate("{% $states.errorOutput.Error %}", false, false),
                "STATES_ERROR_OUTPUT_NOT_AVAILABLE"
        );
    }

    @Test
    void allowsErrorOutputInsideCatcher() {
        assertThat(validate(
                "{% $states.errorOutput.Error %}",
                false,
                true
        )).isEmpty();
    }

    @Test
    void rejectsStatesIntrinsicBeforeLibraryParsing() {
        List<AslValidationIssue> issues = validate(
                "{% States.Format('{}', $states.input.id) %}",
                false,
                false
        );

        assertCode(issues, "STATES_INTRINSIC_NOT_ALLOWED");
        assertThat(issues)
                .noneMatch(issue -> "INVALID_JSONATA_SYNTAX".equals(issue.code()));
    }

    @Test
    void parserErrorContainsUsefulDetail() {
        List<AslValidationIssue> issues = validate(
                "{% $states.input[ %}",
                false,
                false
        );

        assertThat(issues)
                .filteredOn(issue -> "INVALID_JSONATA_SYNTAX".equals(issue.code()))
                .allSatisfy(issue ->
                        assertThat(issue.message())
                                .startsWith("JSONata expression could not be parsed:")
                                .doesNotEndWith(": ")
                );
    }

    private List<AslValidationIssue> validate(
            String expression,
            boolean allowResult,
            boolean allowErrorOutput
    ) {
        List<AslValidationIssue> issues = new ArrayList<>();
        validator.validate(
                expression,
                "$.Expression",
                allowResult,
                allowErrorOutput,
                issues
        );
        return issues;
    }

    private void assertCode(List<AslValidationIssue> issues, String code) {
        assertThat(issues)
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo(code);
                    assertThat(issue.category()).isEqualTo(AslValidationCategory.DIALECT);
                });
    }
}
