package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.enums.AslStateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class SucceedStateExecutorTest {
    private ObjectMapper objectMapper;
    private SucceedStateExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executor = new SucceedStateExecutor(
                new AslJsonataEvaluator(objectMapper, 2000, 100)
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
    void supportsSucceedStateType() {
        assertThat(executor.supportedType()).isEqualTo(AslStateType.SUCCEED);
    }

    @Test
    void usesInputAsDefaultOutput() {
        ObjectNode input = objectMapper.createObjectNode().put("value", "result");

        StateOutcome.Succeed outcome = (StateOutcome.Succeed) executor.execute(
                objectMapper.readTree("{\"Type\":\"Succeed\"}"),
                context(input, objectMapper.createObjectNode())
        );

        assertThat(outcome.output().get("value").stringValue()).isEqualTo("result");
    }

    @Test
    void evaluatesOutputExpression() {
        JsonNode state = objectMapper.readTree("""
                {
                  "Type": "Succeed",
                  "Output": {"status": "{% 'done-' & $states.input.id %}"}
                }
                """);
        ObjectNode input = objectMapper.createObjectNode().put("id", "5");

        StateOutcome.Succeed outcome = (StateOutcome.Succeed) executor.execute(
                state,
                context(input, objectMapper.createObjectNode())
        );

        assertThat(outcome.output().get("status").stringValue()).isEqualTo("done-5");
    }

    @Test
    void passesVariablesThroughToOutcome() {
        ObjectNode variables = objectMapper.createObjectNode().put("retained", "yes");

        StateOutcome.Succeed outcome = (StateOutcome.Succeed) executor.execute(
                objectMapper.readTree("{\"Type\":\"Succeed\"}"),
                context(objectMapper.createObjectNode(), variables)
        );

        assertThat(outcome.variables().get("retained").stringValue()).isEqualTo("yes");
    }
}
