package com.job.scheduler.workflow.asl.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AslSimpleStateDefinitionValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AslMachineDefinitionValidator validator =
            new AslMachineDefinitionValidator(
                    new AslStateDefinitionValidator(new AslJsonataExpressionValidator()),
                    new AslMachineGraphValidator()
            );

    @Test
    void acceptsPassWithNext() {
        assertValid("""
                {
                  "StartAt": "Prepare",
                  "States": {
                    "Prepare": {
                      "Type": "Pass",
                      "Next": "Done"
                    },
                    "Done": {
                      "Type": "Succeed"
                    }
                  }
                }
                """);
    }

    @Test
    void acceptsPassWithEnd() {
        assertValid(machineWithState("""
                {
                  "Type": "Pass",
                  "End": true
                }
                """));
    }

    @Test
    void acceptsPassAssignAndOutput() {
        assertValid(machineWithState("""
                {
                  "Type": "Pass",
                  "Comment": "Prepare output",
                  "Assign": {
                    "customerId": "{% $states.input.customerId %}"
                  },
                  "Output": {
                    "accepted": true,
                    "customerId": "{% $states.input.customerId %}"
                  },
                  "End": true
                }
                """));
    }

    @Test
    void rejectsPassWithoutTransition() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Pass"
                        }
                        """),
                "$.States.State",
                "NEXT_END_EXACTLY_ONE",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsPassWithNextAndEnd() {
        assertIssue(
                """
                {
                  "StartAt": "State",
                  "States": {
                    "State": {
                      "Type": "Pass",
                      "Next": "Done",
                      "End": true
                    },
                    "Done": {
                      "Type": "Succeed"
                    }
                  }
                }
                """,
                "$.States.State",
                "NEXT_END_EXACTLY_ONE",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsPassWithFalseEnd() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Pass",
                          "End": false
                        }
                        """),
                "$.States.State.End",
                "INVALID_END",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsPassUnknownNextTarget() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Pass",
                          "Next": "Missing"
                        }
                        """),
                "$.States.State.Next",
                "NEXT_STATE_NOT_FOUND",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsPassResource() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Pass",
                          "Resource": "scheduler://cleanup",
                          "End": true
                        }
                        """),
                "$.States.State.Resource",
                "STATE_FIELD_NOT_ALLOWED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsAssignVariableNamedStates() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Pass",
                          "Assign": {
                            "states": {}
                          },
                          "End": true
                        }
                        """),
                "$.States.State.Assign.states",
                "RESERVED_VARIABLE_NAME",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsPassOutputUsingUnavailableResult() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Pass",
                          "Output": "{% $states.result %}",
                          "End": true
                        }
                        """),
                "$.States.State.Output",
                "STATES_RESULT_NOT_AVAILABLE",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void acceptsSucceedWithOutput() {
        assertValid(machineWithState("""
                {
                  "Type": "Succeed",
                  "Comment": "Completed",
                  "Output": {
                    "status": "complete"
                  }
                }
                """));
    }

    @Test
    void rejectsSucceedNext() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Succeed",
                          "Next": "Other"
                        }
                        """),
                "$.States.State.Next",
                "STATE_FIELD_NOT_ALLOWED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsSucceedEnd() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Succeed",
                          "End": true
                        }
                        """),
                "$.States.State.End",
                "STATE_FIELD_NOT_ALLOWED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsSucceedAssign() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Succeed",
                          "Assign": {
                            "value": 1
                          }
                        }
                        """),
                "$.States.State.Assign",
                "STATE_FIELD_NOT_ALLOWED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void acceptsFailWithErrorAndCause() {
        assertValid(machineWithState("""
                {
                  "Type": "Fail",
                  "Comment": "Rejected",
                  "Error": "CustomerRejected",
                  "Cause": "{% 'Customer was rejected' %}"
                }
                """));
    }

    @Test
    void rejectsFailOutput() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Fail",
                          "Output": {}
                        }
                        """),
                "$.States.State.Output",
                "STATE_FIELD_NOT_ALLOWED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsFailAssign() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Fail",
                          "Assign": {
                            "value": 1
                          }
                        }
                        """),
                "$.States.State.Assign",
                "STATE_FIELD_NOT_ALLOWED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsFailNonStringCause() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Fail",
                          "Cause": {
                            "message": "failed"
                          }
                        }
                        """),
                "$.States.State.Cause",
                "STRING_OR_EXPRESSION_REQUIRED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsUnknownReservedError() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Fail",
                          "Error": "States.CustomFailure"
                        }
                        """),
                "$.States.State.Error",
                "INVALID_RESERVED_ERROR",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsUnknownStateType() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Mystery"
                        }
                        """),
                "$.States.State.Type",
                "UNKNOWN_STATE_TYPE",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsNonStringStateComment() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Succeed",
                          "Comment": 42
                        }
                        """),
                "$.States.State.Comment",
                "STATE_COMMENT_NOT_STRING",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsStateQueryLanguage() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Succeed",
                          "QueryLanguage": "JSONata"
                        }
                        """),
                "$.States.State.QueryLanguage",
                "STATE_QUERY_LANGUAGE_NOT_ALLOWED",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsJsonPathOnlyStateField() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Pass",
                          "ResultPath": "$.result",
                          "End": true
                        }
                        """),
                "$.States.State.ResultPath",
                "JSONPATH_FIELD_NOT_ALLOWED",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsMalformedJsonataDelimiters() {
        assertIssue(
                machineWithState("""
                        {
                          "Type": "Succeed",
                          "Output": "{% $states.input"
                        }
                        """),
                "$.States.State.Output",
                "INVALID_JSONATA_DELIMITERS",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void exposesSeparateValidityDimensions() {
        AslValidationResult dialectFailure = validate(machineWithState("""
                {
                  "Type": "Succeed",
                  "QueryLanguage": "JSONata"
                }
                """));

        assertThat(dialectFailure.isLanguageValid()).isTrue();
        assertThat(dialectFailure.isDialectValid()).isFalse();
        assertThat(dialectFailure.isRuntimeSupported()).isTrue();
        assertThat(dialectFailure.isValid()).isFalse();
        assertThat(dialectFailure.isExecutable()).isFalse();

        AslValidationResult runtimeFailure = new AslValidationResult(List.of(
                new AslValidationIssue(
                        "$.States.State",
                        AslValidationCategory.RUNTIME_SUPPORT,
                        "NOT_IMPLEMENTED",
                        "Runtime support is not implemented"
                )
        ));

        assertThat(runtimeFailure.isLanguageValid()).isTrue();
        assertThat(runtimeFailure.isDialectValid()).isTrue();
        assertThat(runtimeFailure.isRuntimeSupported()).isFalse();
        assertThat(runtimeFailure.isValid()).isTrue();
        assertThat(runtimeFailure.isExecutable()).isFalse();
    }

    private void assertValid(String json) {
        AslValidationResult result = validate(json);
        assertThat(result.issues()).isEmpty();
        assertThat(result.isValid()).isTrue();
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
