package com.job.scheduler.workflow.asl.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AslMachineDefinitionValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AslMachineDefinitionValidator validator =
            new AslMachineDefinitionValidator(
                    new AslStateDefinitionValidator(new AslJsonataExpressionValidator()),
                    new AslMachineGraphValidator()
            );

    @Test
    void acceptsMinimalMachine() {
        AslValidationResult result = validate("""
                {
                  "StartAt": "Done",
                  "States": {
                    "Done": {
                      "Type": "Succeed"
                    }
                  }
                }
                """);

        assertThat(result.isValid()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void acceptsMachineAndStateComments() {
        AslValidationResult result = validate("""
                {
                  "Comment": "First scheduler workflow",
                  "StartAt": "Done",
                  "States": {
                    "Done": {
                      "Type": "Succeed",
                      "Comment": "Workflow completed"
                    }
                  }
                }
                """);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void acceptsMachineTimeout() {
        AslValidationResult result = validate("""
                {
                  "TimeoutSeconds": 30,
                  "StartAt": "Done",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void rejectsNonObjectRoot() {
        assertIssue(validate("[]"), "$", "MACHINE_NOT_OBJECT", AslValidationCategory.ASL);
    }

    @Test
    void rejectsMissingStartAt() {
        assertIssue(validate("""
                {
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """), "$.StartAt", "INVALID_START_AT", AslValidationCategory.ASL);
    }

    @Test
    void rejectsMissingStates() {
        assertIssue(validate("""
                {
                  "StartAt": "Done"
                }
                """), "$.States", "STATES_NOT_OBJECT", AslValidationCategory.ASL);
    }

    @Test
    void rejectsEmptyStates() {
        assertIssue(validate("""
                {
                  "StartAt": "Done",
                  "States": {}
                }
                """), "$.States", "STATES_EMPTY", AslValidationCategory.ASL);
    }

    @Test
    void rejectsUnknownStartAt() {
        assertIssue(validate("""
                {
                  "StartAt": "Missing",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """), "$.StartAt", "START_STATE_NOT_FOUND", AslValidationCategory.ASL);
    }

    @Test
    void rejectsNonStringComment() {
        assertIssue(validate("""
                {
                  "Comment": 42,
                  "StartAt": "Done",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """), "$.Comment", "COMMENT_NOT_STRING", AslValidationCategory.ASL);
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertIssue(validate("""
                {
                  "TimeoutSeconds": 0,
                  "StartAt": "Done",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """), "$.TimeoutSeconds", "INVALID_MACHINE_TIMEOUT", AslValidationCategory.ASL);
    }

    @Test
    void acceptsCompatibilityQueryLanguageJsonata() {
        AslValidationResult result = validate("""
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Done",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void rejectsOtherQueryLanguage() {
        assertIssue(validate("""
                {
                  "QueryLanguage": "JSONPath",
                  "StartAt": "Done",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """), "$.QueryLanguage", "UNSUPPORTED_QUERY_LANGUAGE", AslValidationCategory.DIALECT);
    }

    @Test
    void rejectsVersion() {
        assertIssue(validate("""
                {
                  "Version": "1.0",
                  "StartAt": "Done",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """), "$.Version", "VERSION_NOT_ALLOWED", AslValidationCategory.DIALECT);
    }

    @Test
    void rejectsUnknownMachineField() {
        assertIssue(validate("""
                {
                  "Owner": "scheduler",
                  "StartAt": "Done",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """), "$.Owner", "UNKNOWN_MACHINE_FIELD", AslValidationCategory.ASL);
    }

    @Test
    void rejectsOverlongStateName() {
        String stateName = "a".repeat(81);
        JsonNode definition = objectMapper.createObjectNode()
                .put("StartAt", stateName)
                .set("States", objectMapper.createObjectNode()
                        .set(stateName, objectMapper.createObjectNode().put("Type", "Succeed")));

        AslValidationResult result = validator.validate(definition);

        assertIssue(
                result,
                "$.States." + stateName,
                "STATE_NAME_TOO_LONG",
                AslValidationCategory.ASL
        );
    }

    @Test
    void countsUnicodeCodePointsForStateNameLimit() {
        String stateName = "😀".repeat(80);
        JsonNode definition = objectMapper.createObjectNode()
                .put("StartAt", stateName)
                .set("States", objectMapper.createObjectNode()
                        .set(stateName, objectMapper.createObjectNode().put("Type", "Succeed")));

        AslValidationResult result = validator.validate(definition);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void rejectsStateWithoutType() {
        assertIssue(validate("""
                {
                  "StartAt": "Done",
                  "States": {
                    "Done": {
                      "Comment": "Missing Type"
                    }
                  }
                }
                """), "$.States.Done.Type", "INVALID_STATE_TYPE", AslValidationCategory.ASL);
    }

    private AslValidationResult validate(String json) {
        return validator.validate(objectMapper.readTree(json));
    }

    private void assertIssue(
            AslValidationResult result,
            String location,
            String code,
            AslValidationCategory category
    ) {
        assertThat(result.isValid()).isFalse();
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.location()).isEqualTo(location);
                    assertThat(issue.code()).isEqualTo(code);
                    assertThat(issue.category()).isEqualTo(category);
                });
    }
}
