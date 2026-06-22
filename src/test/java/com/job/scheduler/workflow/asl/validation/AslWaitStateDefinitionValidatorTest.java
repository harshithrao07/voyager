package com.job.scheduler.workflow.asl.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AslWaitStateDefinitionValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AslMachineDefinitionValidator validator =
            new AslMachineDefinitionValidator(
                    new AslStateDefinitionValidator(new AslJsonataExpressionValidator()),
                    new AslMachineGraphValidator()
            );

    @Test
    void acceptsWaitWithSeconds() {
        AslValidationResult result = validate(machineWithState("""
                {
                  "Type": "Wait",
                  "Seconds": 0,
                  "End": true
                }
                """));

        assertThat(result.isValid()).isTrue();
        assertThat(result.isRuntimeSupported()).isTrue();
        assertThat(result.isExecutable()).isTrue();
    }

    @Test
    void acceptsWaitWithTimestampExpressionAndNext() {
        AslValidationResult result = validate("""
                {
                  "StartAt": "Wait",
                  "States": {
                    "Wait": {
                      "Type": "Wait",
                      "Timestamp": "{% $states.input.wakeAt %}",
                      "Assign": {
                        "waiting": true
                      },
                      "Output": "{% $states.input %}",
                      "Next": "Done"
                    },
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);

        assertThat(result.isValid()).isTrue();
        assertThat(result.isExecutable()).isTrue();
    }

    @Test
    void acceptsRfc3339Timestamp() {
        AslValidationResult result = validate(machineWithState("""
                {
                  "Type": "Wait",
                  "Timestamp": "2026-06-21T10:15:30+05:30",
                  "End": true
                }
                """));

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void rejectsWaitWithoutTimeField() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Wait",
                          "End": true
                        }
                        """)),
                "$.States.State",
                "WAIT_TIME_EXACTLY_ONE",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsWaitWithSecondsAndTimestamp() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Wait",
                          "Seconds": 10,
                          "Timestamp": "2026-06-21T10:15:30Z",
                          "End": true
                        }
                        """)),
                "$.States.State",
                "WAIT_TIME_EXACTLY_ONE",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsNegativeSeconds() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Wait",
                          "Seconds": -1,
                          "End": true
                        }
                        """)),
                "$.States.State.Seconds",
                "NON_NEGATIVE_INTEGER_OR_EXPRESSION_REQUIRED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsStaticStringSeconds() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Wait",
                          "Seconds": "10",
                          "End": true
                        }
                        """)),
                "$.States.State.Seconds",
                "JSONATA_EXPRESSION_REQUIRED",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsInvalidTimestamp() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Wait",
                          "Timestamp": "tomorrow",
                          "End": true
                        }
                        """)),
                "$.States.State.Timestamp",
                "INVALID_TIMESTAMP",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsLowercaseTimestampSeparators() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Wait",
                          "Timestamp": "2026-06-21t10:15:30z",
                          "End": true
                        }
                        """)),
                "$.States.State.Timestamp",
                "INVALID_TIMESTAMP",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsResultInWaitOutput() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Wait",
                          "Seconds": 1,
                          "Output": "{% $states.result %}",
                          "End": true
                        }
                        """)),
                "$.States.State.Output",
                "STATES_RESULT_NOT_AVAILABLE",
                AslValidationCategory.DIALECT
        );
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
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.location()).isEqualTo(location);
                    assertThat(issue.code()).isEqualTo(code);
                    assertThat(issue.category()).isEqualTo(category);
                });
    }

    private String machineWithState(String state) {
        return """
                {
                  "StartAt": "State",
                  "States": {
                    "State": %s
                  }
                }
                """.formatted(state);
    }
}
