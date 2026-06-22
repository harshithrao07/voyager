package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailStateExecutorTest {
    private ObjectMapper objectMapper;
    private FailStateExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executor = new FailStateExecutor(
                new AslJsonataEvaluator(objectMapper, 100, 100)
        );
    }

    @Test
    void evaluatesDynamicErrorAndCause() {
        var state = objectMapper.readTree("""
                {
                  "Type": "Fail",
                  "Error": "{% $states.input.errorName %}",
                  "Cause": "{% 'Order ' & $states.input.orderId & ' failed' %}"
                }
                """);
        var input = objectMapper.createObjectNode()
                .put("errorName", "Payment.Declined")
                .put("orderId", "100");

        StateOutcome.Fail outcome = (StateOutcome.Fail) executor.execute(
                state,
                new StateExecutionContext(
                        input,
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode()
                )
        );

        assertThat(outcome.error()).isEqualTo("Payment.Declined");
        assertThat(outcome.cause()).isEqualTo("Order 100 failed");
    }

    @Test
    void preservesMissingOptionalErrorAndCause() {
        var outcome = (StateOutcome.Fail) executor.execute(
                objectMapper.readTree("{\"Type\":\"Fail\"}"),
                new StateExecutionContext(
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode()
                )
        );

        assertThat(outcome.error()).isNull();
        assertThat(outcome.cause()).isNull();
    }

    @Test
    void rejectsNonStringDynamicError() {
        var state = objectMapper.readTree("""
                {
                  "Type": "Fail",
                  "Error": "{% 42 %}"
                }
                """);

        assertThatThrownBy(() -> executor.execute(
                state,
                new StateExecutionContext(
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode()
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Fail Error must evaluate to a string");
    }
}
