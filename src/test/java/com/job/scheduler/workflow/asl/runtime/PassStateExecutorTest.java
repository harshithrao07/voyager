package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.enums.AslStateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class PassStateExecutorTest {
    private ObjectMapper objectMapper;
    private PassStateExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AslJsonataEvaluator jsonataEvaluator =
                new AslJsonataEvaluator(objectMapper, 2000, 100);
        executor = new PassStateExecutor(
                jsonataEvaluator,
                new AslVariableAssignmentEvaluator(jsonataEvaluator, objectMapper)
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
    void supportsPassStateType() {
        assertThat(executor.supportedType()).isEqualTo(AslStateType.PASS);
    }

    @Test
    void continuesToNextWithInputAsDefaultOutput() {
        JsonNode state = objectMapper.readTree("""
                {"Type": "Pass", "Next": "NextState"}
                """);
        ObjectNode input = objectMapper.createObjectNode().put("value", 7);

        StateOutcome.Continue outcome = (StateOutcome.Continue) executor.execute(
                state,
                context(input, objectMapper.createObjectNode())
        );

        assertThat(outcome.nextStateName()).isEqualTo("NextState");
        assertThat(outcome.output().get("value").intValue()).isEqualTo(7);
    }

    @Test
    void evaluatesOutputExpression() {
        JsonNode state = objectMapper.readTree("""
                {
                  "Type": "Pass",
                  "Next": "NextState",
                  "Output": {"doubled": "{% $states.input.value * 2 %}"}
                }
                """);
        ObjectNode input = objectMapper.createObjectNode().put("value", 21);

        StateOutcome.Continue outcome = (StateOutcome.Continue) executor.execute(
                state,
                context(input, objectMapper.createObjectNode())
        );

        assertThat(outcome.output().get("doubled").intValue()).isEqualTo(42);
    }

    @Test
    void returnsSucceedWhenEndIsTrue() {
        JsonNode state = objectMapper.readTree("""
                {"Type": "Pass", "End": true}
                """);
        ObjectNode input = objectMapper.createObjectNode().put("done", true);

        StateOutcome outcome = executor.execute(
                state,
                context(input, objectMapper.createObjectNode())
        );

        assertThat(outcome).isInstanceOf(StateOutcome.Succeed.class);
        assertThat(((StateOutcome.Succeed) outcome).output().get("done").booleanValue())
                .isTrue();
    }

    @Test
    void appliesAssignToVariables() {
        JsonNode state = objectMapper.readTree("""
                {
                  "Type": "Pass",
                  "Next": "NextState",
                  "Assign": {"orderId": "{% $states.input.id %}"}
                }
                """);
        ObjectNode input = objectMapper.createObjectNode().put("id", "abc");

        StateOutcome.Continue outcome = (StateOutcome.Continue) executor.execute(
                state,
                context(input, objectMapper.createObjectNode())
        );

        assertThat(outcome.variables().get("orderId").stringValue()).isEqualTo("abc");
    }
}
