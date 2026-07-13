package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStateExecutorTest {
    @Test
    void evaluatesArgumentsAndReturnsDurableDispatchRequest() {
        ObjectMapper objectMapper = new ObjectMapper();
        TaskStateExecutor executor = new TaskStateExecutor(
                new AslJsonataEvaluator(objectMapper, 100, 100)
        );
        var state = objectMapper.readTree("""
                {
                  "Type": "Task",
                  "Resource": "voyager://send-email",
                  "Arguments": {
                    "to": "{% $states.input.email %}",
                    "subject": "Welcome",
                    "body": "{% 'Hello ' & $states.input.name %}"
                  },
                  "TimeoutSeconds": "{% 30 %}",
                  "HeartbeatSeconds": 10,
                  "End": true
                }
                """);
        var input = objectMapper.createObjectNode()
                .put("email", "ada@example.com")
                .put("name", "Ada");

        StateOutcome.DispatchTask outcome =
                (StateOutcome.DispatchTask) executor.execute(
                        state,
                        new StateExecutionContext(
                                input,
                                objectMapper.createObjectNode(),
                                objectMapper.createObjectNode()
                        )
                );

        assertThat(outcome.resource()).isEqualTo("voyager://send-email");
        assertThat(outcome.arguments().get("to").stringValue())
                .isEqualTo("ada@example.com");
        assertThat(outcome.arguments().get("body").stringValue())
                .isEqualTo("Hello Ada");
        assertThat(outcome.timeoutSeconds()).isEqualTo(30L);
        assertThat(outcome.heartbeatSeconds()).isEqualTo(10L);
    }
}
