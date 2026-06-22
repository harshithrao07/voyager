package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChoiceStateExecutorTest {
    private ObjectMapper objectMapper;
    private ChoiceStateExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AslJsonataEvaluator evaluator =
                new AslJsonataEvaluator(objectMapper, 100, 100);
        executor = new ChoiceStateExecutor(
                evaluator,
                new AslVariableAssignmentEvaluator(evaluator, objectMapper)
        );
    }

    @Test
    void selectsFirstMatchingRuleAndUsesOnlyRuleAssignAndOutput() {
        var state = read("""
                {
                  "Type": "Choice",
                  "Choices": [
                    {
                      "Condition": "{% $states.input.total >= 100 %}",
                      "Assign": {
                        "route": "large"
                      },
                      "Output": {
                        "selected": "large"
                      },
                      "Next": "LargeOrder"
                    },
                    {
                      "Condition": "{% true %}",
                      "Next": "FallbackRule"
                    }
                  ],
                  "Default": "DefaultRoute",
                  "Assign": {
                    "route": "default"
                  },
                  "Output": {
                    "selected": "default"
                  }
                }
                """);
        var context = context(
                objectMapper.createObjectNode().put("total", 120)
        );

        StateOutcome.Continue outcome =
                (StateOutcome.Continue) executor.execute(state, context);

        assertThat(outcome.nextStateName()).isEqualTo("LargeOrder");
        assertThat(outcome.output().get("selected").stringValue())
                .isEqualTo("large");
        assertThat(outcome.variables().get("route").stringValue())
                .isEqualTo("large");
    }

    @Test
    void usesTopLevelAssignAndOutputOnlyForDefault() {
        var state = read("""
                {
                  "Type": "Choice",
                  "Choices": [
                    {
                      "Condition": "{% false %}",
                      "Next": "Matched"
                    }
                  ],
                  "Default": "DefaultRoute",
                  "Assign": {
                    "route": "default"
                  },
                  "Output": {
                    "selected": "default"
                  }
                }
                """);

        StateOutcome.Continue outcome =
                (StateOutcome.Continue) executor.execute(
                        state,
                        context(objectMapper.createObjectNode())
                );

        assertThat(outcome.nextStateName()).isEqualTo("DefaultRoute");
        assertThat(outcome.output().get("selected").stringValue())
                .isEqualTo("default");
        assertThat(outcome.variables().get("route").stringValue())
                .isEqualTo("default");
    }

    @Test
    void failsWhenNoRuleMatchesAndNoDefaultExists() {
        var state = read("""
                {
                  "Type": "Choice",
                  "Choices": [
                    {
                      "Condition": "{% false %}",
                      "Next": "Matched"
                    }
                  ]
                }
                """);

        StateOutcome.Fail outcome = (StateOutcome.Fail) executor.execute(
                state,
                context(objectMapper.createObjectNode())
        );

        assertThat(outcome.error()).isEqualTo("States.NoChoiceMatched");
    }

    @Test
    void rejectsNonBooleanConditionResult() {
        var state = read("""
                {
                  "Type": "Choice",
                  "Choices": [
                    {
                      "Condition": "{% 'yes' %}",
                      "Next": "Matched"
                    }
                  ]
                }
                """);

        assertThatThrownBy(() -> executor.execute(
                state,
                context(objectMapper.createObjectNode())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Choice Condition must evaluate to a boolean");
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

    private tools.jackson.databind.JsonNode read(String json) {
        return objectMapper.readTree(json);
    }
}
