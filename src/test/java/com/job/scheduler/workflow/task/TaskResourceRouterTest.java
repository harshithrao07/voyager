package com.job.scheduler.workflow.task;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskResourceRouterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void routesToTheFirstSupportingResource() {
        JsonNode expected = objectMapper.createObjectNode().put("ok", true);
        TaskResource webhook = new StubResource("voyager", "webhook", expected);
        TaskResource email = new StubResource("voyager", "send-email", null);
        TaskResourceRouter router =
                new TaskResourceRouter(List.of(email, webhook));

        JsonNode output = router.execute(
                "voyager://webhook",
                objectMapper.createObjectNode()
        );

        assertThat(output).isEqualTo(expected);
    }

    @Test
    void failsWithStableErrorWhenNoResourceSupportsTheUri() {
        TaskResourceRouter router = new TaskResourceRouter(List.of());

        assertThatThrownBy(() -> router.execute(
                "https://example.com/task",
                objectMapper.createObjectNode()
        ))
                .isInstanceOf(TaskResourceException.class)
                .hasMessageContaining("Unsupported Task resource");
        assertThat(unsupportedError(router))
                .isEqualTo(TaskResourceErrors.TASK_FAILED);
    }

    private String unsupportedError(TaskResourceRouter router) {
        try {
            router.execute("nope://x", objectMapper.createObjectNode());
            return null;
        } catch (TaskResourceException exception) {
            return exception.error();
        }
    }

    private record StubResource(String scheme, String operation, JsonNode output)
            implements TaskResource {
        @Override
        public boolean supports(URI resource) {
            return scheme.equals(resource.getScheme())
                    && operation.equals(operation(resource));
        }

        @Override
        public JsonNode execute(URI resource, JsonNode arguments) {
            return output;
        }
    }
}
