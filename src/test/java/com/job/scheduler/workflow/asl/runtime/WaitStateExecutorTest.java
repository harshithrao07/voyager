package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitStateExecutorTest {
    private static final Instant NOW =
            Instant.parse("2026-06-21T10:00:00Z");

    private ObjectMapper objectMapper;
    private WaitStateExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AslJsonataEvaluator evaluator =
                new AslJsonataEvaluator(objectMapper, 2000, 100);
        executor = new WaitStateExecutor(
                evaluator,
                new AslVariableAssignmentEvaluator(evaluator, objectMapper),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void evaluatesSecondsAndPreparesNextStateData() {
        var state = objectMapper.readTree("""
                {
                  "Type": "Wait",
                  "Seconds": "{% $states.input.delay %}",
                  "Assign": {
                    "waited": true
                  },
                  "Output": {
                    "orderId": "{% $states.input.orderId %}"
                  },
                  "Next": "Done"
                }
                """);
        var input = objectMapper.createObjectNode()
                .put("delay", 30)
                .put("orderId", "100");

        StateOutcome.Waiting outcome =
                (StateOutcome.Waiting) executor.execute(
                        state,
                        context(input)
                );

        assertThat(outcome.wakeAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(outcome.nextStateName()).isEqualTo("Done");
        assertThat(outcome.output().get("orderId").stringValue())
                .isEqualTo("100");
        assertThat(outcome.variables().get("waited").booleanValue()).isTrue();
    }

    @Test
    void evaluatesTimestampAndSupportsTerminalWait() {
        var state = objectMapper.readTree("""
                {
                  "Type": "Wait",
                  "Timestamp": "{% $states.input.wakeAt %}",
                  "End": true
                }
                """);
        var input = objectMapper.createObjectNode()
                .put("wakeAt", "2026-06-21T11:00:00+01:00");

        StateOutcome.Waiting outcome =
                (StateOutcome.Waiting) executor.execute(
                        state,
                        context(input)
                );

        assertThat(outcome.wakeAt()).isEqualTo(NOW);
        assertThat(outcome.nextStateName()).isNull();
        assertThat(outcome.output()).isEqualTo(input);
    }

    @Test
    void rejectsNonIntegerSecondsResult() {
        var state = objectMapper.readTree("""
                {
                  "Type": "Wait",
                  "Seconds": "{% 1.5 %}",
                  "End": true
                }
                """);

        assertThatThrownBy(() -> executor.execute(
                state,
                context(objectMapper.createObjectNode())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Wait Seconds must evaluate to a non-negative integer"
                );
    }

    private StateExecutionContext context(
            tools.jackson.databind.JsonNode input
    ) {
        return new StateExecutionContext(
                input,
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode()
        );
    }
}
