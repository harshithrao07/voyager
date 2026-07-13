package com.job.scheduler.workflow.asl.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AslMachineGraphValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AslMachineDefinitionValidator validator =
            new AslMachineDefinitionValidator(
                    new AslStateDefinitionValidator(new AslJsonataExpressionValidator()),
                    new AslMachineGraphValidator()
            );

    @Test
    void rejectsUnreachableState() {
        assertIssue(
                validate("""
                        {
                          "StartAt": "Done",
                          "States": {
                            "Done": {"Type": "Succeed"},
                            "Unused": {"Type": "Fail"}
                          }
                        }
                        """),
                "$.States.Unused",
                "STATE_UNREACHABLE"
        );
    }

    @Test
    void acceptsCycleWithExitToTerminalState() {
        AslValidationResult result = validate("""
                {
                  "StartAt": "LoopA",
                  "States": {
                    "LoopA": {
                      "Type": "Choice",
                      "Choices": [
                        {
                          "Condition": "{% $states.input.done %}",
                          "Next": "Done"
                        }
                      ],
                      "Default": "LoopB"
                    },
                    "LoopB": {
                      "Type": "Pass",
                      "Next": "LoopA"
                    },
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);

        assertThat(result.issues())
                .noneMatch(issue -> "NO_TERMINATING_PATH".equals(issue.code()));
    }

    @Test
    void rejectsClosedCycleWithoutTerminalPath() {
        AslValidationResult result = validate("""
                {
                  "StartAt": "LoopA",
                  "States": {
                    "LoopA": {
                      "Type": "Pass",
                      "Next": "LoopB"
                    },
                    "LoopB": {
                      "Type": "Pass",
                      "Next": "LoopA"
                    }
                  }
                }
                """);

        assertThat(result.issues())
                .filteredOn(issue -> "NO_TERMINATING_PATH".equals(issue.code()))
                .hasSize(2);
    }

    @Test
    void choiceWithoutDefaultHasFailureTerminationPath() {
        AslValidationResult result = validate("""
                {
                  "StartAt": "Route",
                  "States": {
                    "Route": {
                      "Type": "Choice",
                      "Choices": [
                        {
                          "Condition": "{% false %}",
                          "Next": "Loop"
                        }
                      ]
                    },
                    "Loop": {
                      "Type": "Pass",
                      "Next": "Route"
                    }
                  }
                }
                """);

        assertThat(result.issues())
                .noneMatch(issue -> "NO_TERMINATING_PATH".equals(issue.code()));
    }

    @Test
    void catchTargetParticipatesInReachability() {
        AslValidationResult result = validate("""
                {
                  "StartAt": "Task",
                  "States": {
                    "Task": {
                      "Type": "Task",
                      "Resource": "voyager://cleanup",
                      "Catch": [
                        {
                          "ErrorEquals": ["States.ALL"],
                          "Next": "Recovered"
                        }
                      ],
                      "End": true
                    },
                    "Recovered": {"Type": "Succeed"}
                  }
                }
                """);

        assertThat(result.issues())
                .noneMatch(issue -> "STATE_UNREACHABLE".equals(issue.code()));
    }

    @Test
    void acceptsParallelAndCoreMapAsRuntimeSupported() {
        AslValidationResult result = validate("""
                {
                  "StartAt": "ParallelWork",
                  "States": {
                    "ParallelWork": {
                      "Type": "Parallel",
                      "Branches": [
                        {
                          "StartAt": "BranchDone",
                          "States": {
                            "BranchDone": {"Type": "Succeed"}
                          }
                        }
                      ],
                      "Next": "MapWork"
                    },
                    "MapWork": {
                      "Type": "Map",
                      "Items": [],
                      "ItemProcessor": {
                        "StartAt": "ItemDone",
                        "States": {
                          "ItemDone": {"Type": "Succeed"}
                        }
                      },
                      "End": true
                    }
                  }
                }
                """);

        assertThat(result.isValid()).isTrue();
        assertThat(result.isRuntimeSupported()).isTrue();
        assertThat(result.issues())
                .filteredOn(issue -> issue.category() == AslValidationCategory.RUNTIME_SUPPORT)
                .isEmpty();
    }

    private AslValidationResult validate(String json) {
        return validator.validate(objectMapper.readTree(json));
    }

    private void assertIssue(AslValidationResult result, String location, String code) {
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.location()).isEqualTo(location);
                    assertThat(issue.code()).isEqualTo(code);
                    assertThat(issue.category()).isEqualTo(AslValidationCategory.ASL);
                });
    }
}
