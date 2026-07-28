package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionTestCaseDTO;
import com.job.scheduler.dto.WorkflowAiProposedFunctionDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowAiProposedFunctionSafetyValidatorTest {

    private final WorkflowAiProposedFunctionSafetyValidator validator =
            new WorkflowAiProposedFunctionSafetyValidator(new ObjectMapper());

    @Test
    void acceptsRuntimeProvidedCredentialsAndValidJsonTests() {
        WorkflowAiProposedFunctionDTO function = function("""
                import json
                import os
                import sys

                payload = json.load(sys.stdin)
                api_key = os.environ["WEATHER_API_KEY"]
                print(json.dumps({"city": payload["city"], "configured": bool(api_key)}))
                """, List.of(new FunctionTestCaseDTO(
                "city",
                "{\"city\":\"Mangaluru\"}",
                "{\"city\":\"Mangaluru\",\"configured\":true}",
                null
        )));

        assertThat(validator.validate(function)).isEmpty();
    }

    @Test
    void rejectsCredentialPlaceholders() {
        WorkflowAiProposedFunctionDTO function = function(
                "api_key = \"YOUR_API_KEY\"\nprint(api_key)",
                List.of()
        );

        assertThat(validator.validate(function))
                .anyMatch(issue -> issue.contains("placeholder credential"));
        assertThatThrownBy(() -> validator.assertSafe(function))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("placeholder credential")
                .hasMessageNotContaining("YOUR_API_KEY");
    }

    @Test
    void rejectsEmbeddedCredentialsWithoutEchoingThemInTheIssue() {
        WorkflowAiProposedFunctionDTO function = function(
                "token = \"ghp_123456789012345678901234567890123456\"\nprint(token)",
                List.of()
        );

        assertThat(validator.validate(function))
                .anyMatch(issue -> issue.contains("embedded credential"))
                .allMatch(issue -> !issue.contains("ghp_123456789012345678901234567890123456"));
    }

    @Test
    void rejectsInvalidTestCaseJsonBeforeProvisioning() {
        WorkflowAiProposedFunctionDTO function = function(
                "print('{}')",
                List.of(new FunctionTestCaseDTO(
                        "broken",
                        "{not-json}",
                        "{\"ok\":true",
                        null
                ))
        );

        assertThat(validator.validate(function))
                .contains(
                        "Test case 'broken' input must be valid JSON.",
                        "Test case 'broken' expectedOutput must be valid JSON."
                );
    }

    @Test
    void acceptsMissingProposalTimeTests() {
        assertThat(validator.validate(function("print('{}')", List.of()))).isEmpty();
    }

    @Test
    void requiresOneExpectedOutcomePerTestCase() {
        WorkflowAiProposedFunctionDTO missingOutcome = function(
                "print('{}')",
                List.of(new FunctionTestCaseDTO("missing", "{}", null, null))
        );
        WorkflowAiProposedFunctionDTO conflictingOutcome = function(
                "print('{}')",
                List.of(new FunctionTestCaseDTO(
                        "conflicting",
                        "{}",
                        "{}",
                        "should fail"
                ))
        );

        assertThat(validator.validate(missingOutcome))
                .contains("Test case 'missing' must define exactly one of expectedOutput or expectedError.");
        assertThat(validator.validate(conflictingOutcome))
                .contains("Test case 'conflicting' must define exactly one of expectedOutput or expectedError.");
    }

    private WorkflowAiProposedFunctionDTO function(
            String sourceCode,
            List<FunctionTestCaseDTO> testCases
    ) {
        return new WorkflowAiProposedFunctionDTO(
                "weather-helper",
                "Transforms weather data",
                71,
                sourceCode,
                testCases,
                "deterministic transformation"
        );
    }
}
