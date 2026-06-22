package com.job.scheduler.workflow.asl.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AslChoiceStateDefinitionValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AslMachineDefinitionValidator validator =
            new AslMachineDefinitionValidator(
                    new AslStateDefinitionValidator(new AslJsonataExpressionValidator()),
                    new AslMachineGraphValidator()
            );

    @Test
    void acceptsChoiceWithDefaultAndRuleDataProcessing() {
        assertValid("""
                {
                  "StartAt": "Route",
                  "States": {
                    "Route": {
                      "Type": "Choice",
                      "Comment": "Route request",
                      "Assign": {
                        "visited": true
                      },
                      "Output": {
                        "request": "{% $states.input %}"
                      },
                      "Choices": [
                        {
                          "Condition": "{% $states.input.total > 1000 %}",
                          "Assign": {
                            "route": "manual"
                          },
                          "Output": {
                            "selected": "manual"
                          },
                          "Next": "Manual"
                        }
                      ],
                      "Default": "Approved"
                    },
                    "Manual": {"Type": "Succeed"},
                    "Approved": {"Type": "Succeed"}
                  }
                }
                """);
    }

    @Test
    void acceptsChoiceWithoutDefault() {
        assertValid("""
                {
                  "StartAt": "Route",
                  "States": {
                    "Route": {
                      "Type": "Choice",
                      "Choices": [
                        {
                          "Condition": "{% true %}",
                          "Next": "Done"
                        }
                      ]
                    },
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);
    }

    @Test
    void rejectsMissingChoices() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice"
                        }
                        """),
                "$.States.Route.Choices",
                "INVALID_CHOICES",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsEmptyChoices() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": []
                        }
                        """),
                "$.States.Route.Choices",
                "INVALID_CHOICES",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsChoiceRuleThatIsNotObject() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [true]
                        }
                        """),
                "$.States.Route.Choices[0]",
                "CHOICE_RULE_NOT_OBJECT",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsStaticCondition() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [
                            {
                              "Condition": "true",
                              "Next": "Done"
                            }
                          ]
                        }
                        """),
                "$.States.Route.Choices[0].Condition",
                "JSONATA_EXPRESSION_REQUIRED",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsMalformedConditionSyntaxThroughMachineValidation() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [
                            {
                              "Condition": "{% $states.input.total > %}",
                              "Next": "Done"
                            }
                          ]
                        }
                        """),
                "$.States.Route.Choices[0].Condition",
                "INVALID_JSONATA_SYNTAX",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsMissingCondition() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [
                            {
                              "Next": "Done"
                            }
                          ]
                        }
                        """),
                "$.States.Route.Choices[0].Condition",
                "JSONATA_EXPRESSION_REQUIRED",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsUnknownChoiceTarget() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [
                            {
                              "Condition": "{% true %}",
                              "Next": "Missing"
                            }
                          ]
                        }
                        """),
                "$.States.Route.Choices[0].Next",
                "TRANSITION_TARGET_NOT_FOUND",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsUnknownDefaultTarget() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [
                            {
                              "Condition": "{% true %}",
                              "Next": "Done"
                            }
                          ],
                          "Default": "Missing"
                        }
                        """),
                "$.States.Route.Default",
                "TRANSITION_TARGET_NOT_FOUND",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsStateLevelNext() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [
                            {
                              "Condition": "{% true %}",
                              "Next": "Done"
                            }
                          ],
                          "Next": "Done"
                        }
                        """),
                "$.States.Route.Next",
                "STATE_FIELD_NOT_ALLOWED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsStateLevelEnd() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [
                            {
                              "Condition": "{% true %}",
                              "Next": "Done"
                            }
                          ],
                          "End": true
                        }
                        """),
                "$.States.Route.End",
                "STATE_FIELD_NOT_ALLOWED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsJsonPathChoiceOperator() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [
                            {
                              "Variable": "$.total",
                              "NumericGreaterThan": 1000,
                              "Next": "Done"
                            }
                          ]
                        }
                        """),
                "$.States.Route.Choices[0].Variable",
                "JSONPATH_CHOICE_NOT_ALLOWED",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsResultInChoiceCondition() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [
                            {
                              "Condition": "{% $states.result.ok %}",
                              "Next": "Done"
                            }
                          ]
                        }
                        """),
                "$.States.Route.Choices[0].Condition",
                "STATES_RESULT_NOT_AVAILABLE",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsErrorOutputInChoiceRuleOutput() {
        assertIssue(
                machineWithChoice("""
                        {
                          "Type": "Choice",
                          "Choices": [
                            {
                              "Condition": "{% true %}",
                              "Output": "{% $states.errorOutput %}",
                              "Next": "Done"
                            }
                          ]
                        }
                        """),
                "$.States.Route.Choices[0].Output",
                "STATES_ERROR_OUTPUT_NOT_AVAILABLE",
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

    private String machineWithChoice(String choice) {
        return """
                {
                  "StartAt": "Route",
                  "States": {
                    "Route": %s,
                    "Done": {"Type": "Succeed"}
                  }
                }
                """.formatted(choice);
    }
}
