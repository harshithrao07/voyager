package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class AslVariableAssignmentEvaluatorTest {
    private ObjectMapper objectMapper;
    private AslVariableAssignmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        evaluator = new AslVariableAssignmentEvaluator(
                new AslJsonataEvaluator(objectMapper, 100, 100),
                objectMapper
        );
    }

    private StateExecutionContext context(JsonNode input, JsonNode variables) {
        return new StateExecutionContext(
                input,
                variables,
                objectMapper.createObjectNode()
        );
    }

    @Test
    void returnsExistingVariablesUnchangedWhenAssignIsNull() {
        ObjectNode variables = objectMapper.createObjectNode().put("foo", "bar");

        JsonNode result = evaluator.apply(
                null,
                context(objectMapper.createObjectNode(), variables)
        );

        assertThat(result.get("foo").stringValue()).isEqualTo("bar");
    }

    @Test
    void returnsEmptyObjectWhenVariablesNullAndAssignNull() {
        JsonNode result = evaluator.apply(
                null,
                context(objectMapper.createObjectNode(), null)
        );

        assertThat(result.isObject()).isTrue();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void replacesNonObjectVariablesWithEmptyObject() {
        JsonNode result = evaluator.apply(
                null,
                context(objectMapper.createObjectNode(), objectMapper.createArrayNode())
        );

        assertThat(result.isObject()).isTrue();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void evaluatesLiteralAssignmentAndKeepsExistingVariables() {
        ObjectNode assign = objectMapper.createObjectNode().put("greeting", "hello");
        ObjectNode existing = objectMapper.createObjectNode().put("keep", "me");

        JsonNode result = evaluator.apply(
                assign,
                context(objectMapper.createObjectNode(), existing)
        );

        assertThat(result.get("greeting").stringValue()).isEqualTo("hello");
        assertThat(result.get("keep").stringValue()).isEqualTo("me");
    }

    @Test
    void evaluatesJsonataExpressionAgainstInput() {
        JsonNode assign = objectMapper.readTree("""
                {"orderId": "{% $states.input.id %}"}
                """);
        JsonNode input = objectMapper.createObjectNode().put("id", "order-99");

        JsonNode result = evaluator.apply(
                assign,
                context(input, objectMapper.createObjectNode())
        );

        assertThat(result.get("orderId").stringValue()).isEqualTo("order-99");
    }

    @Test
    void newAssignmentOverridesExistingVariableWithSameKey() {
        ObjectNode assign = objectMapper.createObjectNode().put("count", 2);
        ObjectNode existing = objectMapper.createObjectNode().put("count", 1);

        JsonNode result = evaluator.apply(
                assign,
                context(objectMapper.createObjectNode(), existing)
        );

        assertThat(result.get("count").intValue()).isEqualTo(2);
    }

    @Test
    void doesNotMutateCallerSuppliedVariables() {
        ObjectNode existing = objectMapper.createObjectNode().put("keep", "me");
        ObjectNode assign = objectMapper.createObjectNode().put("added", "value");

        evaluator.apply(assign, context(objectMapper.createObjectNode(), existing));

        assertThat(existing.has("added")).isFalse();
    }
}
