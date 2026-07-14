package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the compound-state guard that prevents auto-retrying a
 * Parallel/Map whose generation would re-run a mutating MCP task.
 */
class WorkflowInterpreterMcpRetryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private boolean containsMutating(String json) {
        JsonNode state = objectMapper.readTree(json);
        return WorkflowInterpreter.containsMutatingMcpTask(state);
    }

    @Test
    void detectsMutatingMcpTaskInParallelBranch() {
        assertThat(containsMutating("""
                {
                  "Type": "Parallel",
                  "Branches": [{
                    "StartAt": "Write",
                    "States": {
                      "Write": {
                        "Type": "Task",
                        "Resource": "voyager://mcp/crm/create-lead?trust=WRITE",
                        "End": true
                      }
                    }
                  }]
                }
                """)).isTrue();
    }

    @Test
    void detectsMutatingMcpTaskInMapItemProcessor() {
        assertThat(containsMutating("""
                {
                  "Type": "Map",
                  "ItemProcessor": {
                    "StartAt": "Purge",
                    "States": {
                      "Purge": {
                        "Type": "Task",
                        "Resource": "voyager://mcp/crm/purge?trust=DESTRUCTIVE",
                        "End": true
                      }
                    }
                  }
                }
                """)).isTrue();
    }

    @Test
    void detectsMutatingMcpTaskNestedInsideAnotherCompound() {
        assertThat(containsMutating("""
                {
                  "Type": "Parallel",
                  "Branches": [{
                    "StartAt": "InnerMap",
                    "States": {
                      "InnerMap": {
                        "Type": "Map",
                        "ItemProcessor": {
                          "StartAt": "Write",
                          "States": {
                            "Write": {
                              "Type": "Task",
                              "Resource": "voyager://mcp/crm/create-lead?trust=WRITE",
                              "End": true
                            }
                          }
                        },
                        "End": true
                      }
                    }
                  }]
                }
                """)).isTrue();
    }

    @Test
    void ignoresReadOnlyAndNonMcpTasks() {
        assertThat(containsMutating("""
                {
                  "Type": "Parallel",
                  "Branches": [{
                    "StartAt": "Read",
                    "States": {
                      "Read": {
                        "Type": "Task",
                        "Resource": "voyager://mcp/crm/get-customer",
                        "Next": "Notify"
                      },
                      "Notify": {
                        "Type": "Task",
                        "Resource": "voyager://system/webhook",
                        "End": true
                      }
                    }
                  }]
                }
                """)).isFalse();
    }

    @Test
    void ignoresCompoundWithoutNestedStates() {
        assertThat(containsMutating("""
                {"Type": "Task", "Resource": "voyager://mcp/crm/create-lead?trust=WRITE", "End": true}
                """)).isFalse();
    }
}
