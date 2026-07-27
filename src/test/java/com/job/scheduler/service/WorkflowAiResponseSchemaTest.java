package com.job.scheduler.service;

import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowAiResponseSchemaTest {

    @Test
    void closesTheEnvelopeAndRequiresEveryTopLevelField() throws Exception {
        JsonRawSchema raw = (JsonRawSchema) WorkflowAiResponseSchema.responseFormat()
                .jsonSchema()
                .rootElement();
        JsonNode schema = new ObjectMapper().readTree(raw.schema());

        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required").valueStream().map(JsonNode::asText).toList())
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "stage",
                        "message",
                        "aslDefinition",
                        "finalPlan",
                        "draftWorkflowPayload",
                        "resourcePlan"
                ));
        assertThat(schema.path("properties").path("stage").path("enum")).hasSize(6);
        assertThat(WorkflowAiResponseSchema.responseFormat().jsonSchema().name())
                .isEqualTo("voyager_workflow_ai_response");
    }
}
