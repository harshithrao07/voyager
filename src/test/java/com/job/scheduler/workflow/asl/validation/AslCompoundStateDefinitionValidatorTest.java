package com.job.scheduler.workflow.asl.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AslCompoundStateDefinitionValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AslMachineDefinitionValidator validator =
            new AslMachineDefinitionValidator(
                    new AslStateDefinitionValidator(new AslJsonataExpressionValidator()),
                    new AslMachineGraphValidator()
            );

    @Test
    void acceptsParallelWithRecursivelyValidatedBranches() {
        AslValidationResult result = validate("""
                {
                  "StartAt": "FanOut",
                  "States": {
                    "FanOut": {
                      "Type": "Parallel",
                      "Arguments": {
                        "customerId": "{% $states.input.customerId %}"
                      },
                      "Branches": [
                        {
                          "StartAt": "Email",
                          "States": {
                            "Email": {
                              "Type": "Task",
                              "Resource": "scheduler://send-email",
                              "End": true
                            }
                          }
                        },
                        {
                          "StartAt": "Audit",
                          "States": {
                            "Audit": {
                              "Type": "Pass",
                              "End": true
                            }
                          }
                        }
                      ],
                      "Assign": {
                        "branchResults": "{% $states.result %}"
                      },
                      "Output": "{% $states.result %}",
                      "End": true
                    }
                  }
                }
                """);

        assertThat(result.isValid()).isTrue();
        assertThat(result.isRuntimeSupported()).isTrue();
    }

    @Test
    void rejectsParallelWithoutBranches() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Parallel",
                          "End": true
                        }
                        """)),
                "$.States.State.Branches",
                "INVALID_BRANCHES",
                AslValidationCategory.ASL
        );
    }

    @Test
    void reportsExactNestedBranchLocation() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Parallel",
                          "Branches": [
                            {
                              "StartAt": "Call",
                              "States": {
                                "Call": {
                                  "Type": "Task",
                                  "End": true
                                }
                              }
                            }
                          ],
                          "End": true
                        }
                        """)),
                "$.States.State.Branches[0].States.Call.Resource",
                "INVALID_RESOURCE",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsNestedBranchTransitionToParentState() {
        assertIssue(
                validate("""
                        {
                          "StartAt": "FanOut",
                          "States": {
                            "FanOut": {
                              "Type": "Parallel",
                              "Branches": [
                                {
                                  "StartAt": "Branch",
                                  "States": {
                                    "Branch": {
                                      "Type": "Pass",
                                      "Next": "After"
                                    }
                                  }
                                }
                              ],
                              "Next": "After"
                            },
                            "After": {"Type": "Succeed"}
                          }
                        }
                        """),
                "$.States.FanOut.Branches[0].States.Branch.Next",
                "NEXT_STATE_NOT_FOUND",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsParallelBranchAssignmentToOuterVariable() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Parallel",
                          "Assign": {
                            "shared": 1
                          },
                          "Branches": [
                            {
                              "StartAt": "Branch",
                              "States": {
                                "Branch": {
                                  "Type": "Pass",
                                  "Assign": {
                                    "shared": 2
                                  },
                                  "End": true
                                }
                              }
                            }
                          ],
                          "End": true
                        }
                        """)),
                "$.States.State.Branches[0].States.Branch.Assign.shared",
                "OUTER_VARIABLE_REASSIGNMENT",
                AslValidationCategory.ASL
        );
    }

    @Test
    void allowsSiblingBranchesToUseSameInnerVariableName() {
        AslValidationResult result = validate(machineWithState("""
                {
                  "Type": "Parallel",
                  "Branches": [
                    {
                      "StartAt": "First",
                      "States": {
                        "First": {
                          "Type": "Pass",
                          "Assign": {"local": 1},
                          "End": true
                        }
                      }
                    },
                    {
                      "StartAt": "Second",
                      "States": {
                        "Second": {
                          "Type": "Pass",
                          "Assign": {"local": 2},
                          "End": true
                        }
                      }
                    }
                  ],
                  "End": true
                }
                """));

        assertThat(result.issues())
                .noneMatch(issue -> "OUTER_VARIABLE_REASSIGNMENT".equals(issue.code()));
    }

    @Test
    void validatesNestedBranchGraph() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Parallel",
                          "Branches": [
                            {
                              "StartAt": "Loop",
                              "States": {
                                "Loop": {
                                  "Type": "Pass",
                                  "Next": "Loop"
                                }
                              }
                            }
                          ],
                          "End": true
                        }
                        """)),
                "$.States.State.Branches[0].States.Loop",
                "NO_TERMINATING_PATH",
                AslValidationCategory.ASL
        );
    }

    @Test
    void acceptsMapWithSupportedInlineFeatures() {
        AslValidationResult result = validate(machineWithState("""
                {
                  "Type": "Map",
                  "Items": "{% $states.input.orders %}",
                  "ItemSelector": {
                    "order": "{% $states.context.Map.Item.Value %}",
                    "index": "{% $states.context.Map.Item.Index %}"
                  },
                  "ItemProcessor": {
                    "ProcessorConfig": {
                      "Mode": "INLINE"
                    },
                    "StartAt": "Process",
                    "States": {
                      "Process": {
                        "Type": "Task",
                        "Resource": "mcp://orders/process",
                        "End": true
                      }
                    }
                  },
                  "MaxConcurrency": "{% 5 %}",
                  "Assign": {
                    "mapResult": "{% $states.result %}"
                  },
                  "Output": "{% $states.result %}",
                  "End": true
                }
                """));

        assertThat(result.isValid()).isTrue();
        assertThat(result.isRuntimeSupported()).isTrue();
    }

    @Test
    void rejectsUnsupportedMapFeaturesAsRuntimeUnsupported() {
        AslValidationResult result = validate(machineWithState("""
                {
                  "Type": "Map",
                  "ItemReader": {
                    "Resource": "scheduler://read-orders"
                  },
                  "Items": "{% $states.input.orders %}",
                  "ItemBatcher": {
                    "MaxItemsPerBatch": 10
                  },
                  "ResultWriter": {
                    "Resource": "scheduler://write-results"
                  },
                  "ToleratedFailurePercentage": 10.5,
                  "ToleratedFailureCount": 2,
                  "ItemProcessor": {
                    "StartAt": "Done",
                    "States": {
                      "Done": {"Type": "Succeed"}
                    }
                  },
                  "End": true
                }
                """));

        assertThat(result.isRuntimeSupported()).isFalse();
        assertThat(result.issues())
                .filteredOn(issue ->
                        "MAP_FEATURE_RUNTIME_UNSUPPORTED".equals(issue.code()))
                .extracting(AslValidationIssue::location)
                .contains(
                        "$.States.State.ItemReader",
                        "$.States.State.ItemBatcher",
                        "$.States.State.ResultWriter",
                        "$.States.State.ToleratedFailurePercentage",
                        "$.States.State.ToleratedFailureCount"
                );
    }

    @Test
    void acceptsMapWithInputArrayDefaultAndMinimalProcessor() {
        AslValidationResult result = validate(machineWithState("""
                {
                  "Type": "Map",
                  "ItemProcessor": {
                    "StartAt": "Done",
                    "States": {
                      "Done": {"Type": "Succeed"}
                    }
                  },
                  "End": true
                }
                """));

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void rejectsMapWithoutItemProcessor() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Map",
                          "Items": [],
                          "End": true
                        }
                        """)),
                "$.States.State.ItemProcessor",
                "ITEM_PROCESSOR_REQUIRED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsDeprecatedIterator() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Map",
                          "Iterator": {
                            "StartAt": "Done",
                            "States": {
                              "Done": {"Type": "Succeed"}
                            }
                          },
                          "End": true
                        }
                        """)),
                "$.States.State.Iterator",
                "DEPRECATED_ITERATOR_NOT_ALLOWED",
                AslValidationCategory.DIALECT
        );
    }

    @Test
    void rejectsInvalidItemsShape() {
        assertIssue(
                validate(mapWith("""
                        "Items": {"not": "an array"},
                        """)),
                "$.States.State.Items",
                "ITEMS_ARRAY_OR_EXPRESSION_REQUIRED",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsInvalidMapConcurrency() {
        AslValidationResult result = validate(mapWith("""
                "MaxConcurrency": -1,
                """));

        assertThat(result.issues())
                .extracting(AslValidationIssue::code)
                .contains("NON_NEGATIVE_INTEGER_OR_EXPRESSION_REQUIRED");
    }

    @Test
    void reportsExactNestedItemProcessorLocation() {
        assertIssue(
                validate(mapWithProcessor("""
                        {
                          "StartAt": "Missing",
                          "States": {
                            "Done": {"Type": "Succeed"}
                          }
                        }
                        """)),
                "$.States.State.ItemProcessor.StartAt",
                "START_STATE_NOT_FOUND",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsItemProcessorTransitionToParentState() {
        assertIssue(
                validate("""
                        {
                          "StartAt": "Process",
                          "States": {
                            "Process": {
                              "Type": "Map",
                              "ItemProcessor": {
                                "StartAt": "Item",
                                "States": {
                                  "Item": {
                                    "Type": "Pass",
                                    "Next": "After"
                                  }
                                }
                              },
                              "Next": "After"
                            },
                            "After": {"Type": "Succeed"}
                          }
                        }
                        """),
                "$.States.Process.ItemProcessor.States.Item.Next",
                "NEXT_STATE_NOT_FOUND",
                AslValidationCategory.ASL
        );
    }

    @Test
    void rejectsItemProcessorAssignmentToOuterVariable() {
        assertIssue(
                validate(machineWithState("""
                        {
                          "Type": "Map",
                          "Assign": {
                            "shared": 1
                          },
                          "ItemProcessor": {
                            "StartAt": "Item",
                            "States": {
                              "Item": {
                                "Type": "Pass",
                                "Assign": {
                                  "shared": 2
                                },
                                "End": true
                              }
                            }
                          },
                          "End": true
                        }
                        """)),
                "$.States.State.ItemProcessor.States.Item.Assign.shared",
                "OUTER_VARIABLE_REASSIGNMENT",
                AslValidationCategory.ASL
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

    private String mapWith(String fields) {
        return machineWithState("""
                {
                  "Type": "Map",
                  %s
                  "ItemProcessor": {
                    "StartAt": "Done",
                    "States": {
                      "Done": {"Type": "Succeed"}
                    }
                  },
                  "End": true
                }
                """.formatted(fields));
    }

    private String mapWithProcessor(String processor) {
        return machineWithState("""
                {
                  "Type": "Map",
                  "ItemProcessor": %s,
                  "End": true
                }
                """.formatted(processor));
    }
}
