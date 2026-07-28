package com.job.scheduler.service;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;

/**
 * Provider-facing JSON Schema for one workflow-builder reply.
 *
 * <p>The envelope and resource proposal are closed and fully typed. ASL, final plans, and draft
 * workflow definitions deliberately remain open JSON values: ASL state names are user-defined, so
 * pretending they can be represented by a closed strict schema would reject valid workflows.
 * Voyager's existing ASL and invariant validators remain the authority for those dynamic values.
 */
final class WorkflowAiResponseSchema {
    private static final String CONTRACT_NAME = "voyager_workflow_ai_response";
    private static final String CONTRACT = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "stage": {
                  "type": "string",
                  "enum": [
                    "COLLECTING_WORKFLOW_DETAILS",
                    "RESOURCES_PROPOSED",
                    "ASL_READY",
                    "ASL_UNDER_REVIEW",
                    "COLLECTING_SCHEDULE_DETAILS",
                    "PLAN_READY"
                  ]
                },
                "message": {"type": "string"},
                "aslDefinition": {},
                "finalPlan": {},
                "draftWorkflowPayload": {},
                "resourcePlan": {
                  "anyOf": [
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {
                        "functions": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "additionalProperties": false,
                            "properties": {
                              "name": {"type": "string"},
                              "description": {"type": ["string", "null"]},
                              "languageId": {"type": ["integer", "null"]},
                              "sourceCode": {"type": ["string", "null"]},
                              "rationale": {"type": ["string", "null"]}
                            },
                            "required": [
                              "name",
                              "description",
                              "languageId",
                              "sourceCode",
                              "rationale"
                            ]
                          }
                        },
                        "mcpRequirements": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "additionalProperties": false,
                            "properties": {
                              "capability": {"type": "string"},
                              "suggestedToolName": {"type": ["string", "null"]},
                              "reason": {"type": ["string", "null"]},
                              "trustLevelHint": {"type": ["string", "null"]}
                            },
                            "required": [
                              "capability",
                              "suggestedToolName",
                              "reason",
                              "trustLevelHint"
                            ]
                          }
                        }
                      },
                      "required": ["functions", "mcpRequirements"]
                    },
                    {"type": "null"}
                  ]
                }
              },
              "required": [
                "stage",
                "message",
                "aslDefinition",
                "finalPlan",
                "draftWorkflowPayload",
                "resourcePlan"
              ]
            }
            """;

    private static final ResponseFormat RESPONSE_FORMAT = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder()
                    .name(CONTRACT_NAME)
                    .rootElement(JsonRawSchema.from(CONTRACT))
                    .build())
            .build();

    private WorkflowAiResponseSchema() {
    }

    static ResponseFormat responseFormat() {
        return RESPONSE_FORMAT;
    }

    static String contract() {
        return CONTRACT;
    }
}
