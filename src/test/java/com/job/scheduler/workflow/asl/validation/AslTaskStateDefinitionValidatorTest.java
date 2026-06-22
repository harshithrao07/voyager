package com.job.scheduler.workflow.asl.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AslTaskStateDefinitionValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AslMachineDefinitionValidator validator =
            new AslMachineDefinitionValidator(
                    new AslStateDefinitionValidator(new AslJsonataExpressionValidator()),
                    new AslMachineGraphValidator()
            );

    @Test
    void acceptsTaskWithCustomResourceAndEnd() {
        assertValid(machineWithState("""
                {
                  "Type": "Task",
                  "Resource": "mcp://crm/get_customer",
                  "End": true
                }
                """));
    }

    @Test
    void acceptsTaskDataProcessingTimeoutsRetryAndCatch() {
        assertValid("""
                {
                  "StartAt": "CallTool",
                  "States": {
                    "CallTool": {
                      "Type": "Task",
                      "Resource": "scheduler://send-email",
                      "Arguments": {
                        "to": "{% $states.input.email %}"
                      },
                      "Assign": {
                        "result": "{% $states.result %}"
                      },
                      "Output": {
                        "sent": "{% $states.result.sent %}"
                      },
                      "TimeoutSeconds": "{% 30 %}",
                      "HeartbeatSeconds": 10,
                      "Retry": [
                        {
                          "ErrorEquals": ["States.Timeout"],
                          "IntervalSeconds": 2,
                          "MaxAttempts": 3,
                          "BackoffRate": 2.0,
                          "MaxDelaySeconds": 30,
                          "JitterStrategy": "FULL"
                        }
                      ],
                      "Catch": [
                        {
                          "ErrorEquals": ["States.ALL"],
                          "Assign": {
                            "failure": "{% $states.errorOutput %}"
                          },
                          "Output": {
                            "error": "{% $states.errorOutput %}"
                          },
                          "Next": "Failed"
                        }
                      ],
                      "End": true
                    },
                    "Failed": {
                      "Type": "Fail",
                      "Error": "TaskFailed"
                    }
                  }
                }
                """);
    }

    @Test
    void rejectsUnsupportedJitterStrategyAtRuntimeCapabilityValidation() {
        AslValidationResult result = validator.validate(objectMapper.readTree("""
                {
                  "StartAt": "State",
                  "States": {
                    "State": {
                      "Type": "Task",
                      "Resource": "scheduler://cleanup",
                      "Retry": [{
                        "ErrorEquals": ["States.ALL"],
                        "JitterStrategy": "CUSTOM"
                      }],
                      "End": true
                    }
                  }
                }
                """));

        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.code())
                    .isEqualTo("JITTER_STRATEGY_RUNTIME_UNSUPPORTED");
            assertThat(issue.location())
                    .isEqualTo("$.States.State.Retry[0].JitterStrategy");
        });
    }

    @Test
    void rejectsMissingResource() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "End": true
                        }
                        """),
                "$.States.State.Resource",
                "INVALID_RESOURCE",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsResourceWithoutScheme() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "send-email",
                          "End": true
                        }
                        """),
                "$.States.State.Resource",
                "INVALID_RESOURCE",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsTaskWithoutNextOrEnd() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup"
                        }
                        """),
                "$.States.State",
                "NEXT_END_EXACTLY_ONE",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsArgumentsUsingResult() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "Arguments": {
                            "value": "{% $states.result %}"
                          },
                          "End": true
                        }
                        """),
                "$.States.State.Arguments.value",
                "STATES_RESULT_NOT_AVAILABLE",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsArgumentsUsingErrorOutput() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "Arguments": "{% $states.errorOutput %}",
                          "End": true
                        }
                        """),
                "$.States.State.Arguments",
                "STATES_ERROR_OUTPUT_NOT_AVAILABLE",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "TimeoutSeconds": 0,
                          "End": true
                        }
                        """),
                "$.States.State.TimeoutSeconds",
                "POSITIVE_INTEGER_OR_EXPRESSION_REQUIRED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsStaticStringTimeout() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "TimeoutSeconds": "30",
                          "End": true
                        }
                        """),
                "$.States.State.TimeoutSeconds",
                "JSONATA_EXPRESSION_REQUIRED",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsRetryThatIsNotArray() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "Retry": {},
                          "End": true
                        }
                        """),
                "$.States.State.Retry",
                "RETRY_NOT_ARRAY",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsRetryWithoutErrorEquals() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "Retry": [{}],
                          "End": true
                        }
                        """),
                "$.States.State.Retry[0].ErrorEquals",
                "INVALID_ERROR_EQUALS",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsEmptyRetryAndCatchArrays() {
        AslValidationResult result = validate(machineWithState("""
                {
                  "Type": "Task",
                  "Resource": "scheduler://cleanup",
                  "Retry": [],
                  "Catch": [],
                  "End": true
                }
                """));

        assertThat(result.issues())
                .extracting(AslValidationIssue::code)
                .contains("RETRY_EMPTY", "CATCH_EMPTY");
    }

    @Test
    void rejectsStatesAllCombinedWithOtherErrors() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "Retry": [
                            {
                              "ErrorEquals": ["States.Timeout", "States.ALL"]
                            }
                          ],
                          "End": true
                        }
                        """),
                "$.States.State.Retry[0].ErrorEquals",
                "STATES_ALL_MUST_BE_ALONE",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsStatesAllRetrierBeforeAnotherRetrier() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "Retry": [
                            {"ErrorEquals": ["States.ALL"]},
                            {"ErrorEquals": ["States.Timeout"]}
                          ],
                          "End": true
                        }
                        """),
                "$.States.State.Retry[0].ErrorEquals",
                "STATES_ALL_NOT_LAST",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsUnknownReservedErrorInErrorEquals() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "Retry": [
                            {
                              "ErrorEquals": ["States.CustomFailure"]
                            }
                          ],
                          "End": true
                        }
                        """),
                "$.States.State.Retry[0].ErrorEquals[0]",
                "INVALID_RESERVED_ERROR",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsInvalidRetrierNumbers() {
        String definition = machineWithState("""
                {
                  "Type": "Task",
                  "Resource": "scheduler://cleanup",
                  "Retry": [
                    {
                      "ErrorEquals": ["States.Timeout"],
                      "IntervalSeconds": 0,
                      "MaxAttempts": -1,
                      "BackoffRate": 0.5,
                      "MaxDelaySeconds": 0
                    }
                  ],
                  "End": true
                }
                """);

        AslValidationResult result = validate(definition);

        assertThat(result.issues())
                .extracting(AslValidationIssue::code)
                .contains(
                        "POSITIVE_INTEGER_REQUIRED",
                        "NON_NEGATIVE_INTEGER_REQUIRED",
                        "NUMBER_AT_LEAST_ONE_REQUIRED"
                );
    }

    @Test
    void rejectsCatchWithUnknownTarget() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "Catch": [
                            {
                              "ErrorEquals": ["States.ALL"],
                              "Next": "Missing"
                            }
                          ],
                          "End": true
                        }
                        """),
                "$.States.State.Catch[0].Next",
                "TRANSITION_TARGET_NOT_FOUND",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsResultInsideCatcherOutput() {
        assertIssue(
                """
                {
                  "StartAt": "State",
                  "States": {
                    "State": {
                      "Type": "Task",
                      "Resource": "scheduler://cleanup",
                      "Catch": [
                        {
                          "ErrorEquals": ["States.ALL"],
                          "Output": "{% $states.result %}",
                          "Next": "Failed"
                        }
                      ],
                      "End": true
                    },
                    "Failed": {"Type": "Fail"}
                  }
                }
                """,
                "$.States.State.Catch[0].Output",
                "STATES_RESULT_NOT_AVAILABLE",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsStatesIntrinsicFunction() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "Arguments": "{% States.Format('x') %}",
                          "End": true
                        }
                        """),
                "$.States.State.Arguments",
                "STATES_INTRINSIC_NOT_ALLOWED",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsJsonPathPayloadTemplateKeyInsideArguments() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Task",
                          "Resource": "scheduler://cleanup",
                          "Arguments": {
                            "customerId.$": "$.customerId"
                          },
                          "End": true
                        }
                        """),
                "$.States.State.Arguments.customerId.$",
                "JSONPATH_FIELD_NOT_ALLOWED",
                AslValidationCategory.DIALECT
        );
    }

    private void assertValid(String json) {
        AslValidationResult result = validate(json);
        assertThat(result.issues()).isEmpty();
    }

    private void assertIssue(
            String json,
            String location,
            String code,
            AslValidationCategory category
    ) {
        AslValidationResult result = validate(json);
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.location()).isEqualTo(location);
                    assertThat(issue.code()).isEqualTo(code);
                    assertThat(issue.category()).isEqualTo(category);
                });
    }

    private AslValidationResult validate(String json) {
        return validator.validate(objectMapper.readTree(json));
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
