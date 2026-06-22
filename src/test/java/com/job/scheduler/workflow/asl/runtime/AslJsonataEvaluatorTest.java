package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AslJsonataEvaluatorTest {
    private ObjectMapper objectMapper;
    private AslJsonataEvaluator evaluator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        evaluator = new AslJsonataEvaluator(objectMapper, 100, 100);
    }

    @Test
    void evaluatesStatesAndScopeVariablesRecursively() {
        var input = objectMapper.createObjectNode().put("name", "Ada");
        var variables = objectMapper.createObjectNode().put("greeting", "hello");
        var context = objectMapper.createObjectNode().put("StateName", "Welcome");
        var value = objectMapper.createObjectNode()
                .put("message", "{% $greeting & ' ' & $states.input.name %}")
                .put("state", "{% $states.context.StateName %}");

        var result = evaluator.evaluate(
                value,
                new StateExecutionContext(input, variables, context)
        );

        assertThat(result.get("message").stringValue()).isEqualTo("hello Ada");
        assertThat(result.get("state").stringValue()).isEqualTo("Welcome");
    }
}
