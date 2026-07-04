package com.job.scheduler.workflow.task;

import com.job.scheduler.service.FunctionInvocationService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FunctionTaskResourceTest {
    private final FunctionInvocationService invocationService =
            mock(FunctionInvocationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FunctionTaskResource resource =
            new FunctionTaskResource(invocationService);

    @Test
    void supportsFunctionSchemeOnly() {
        assertThat(resource.supports(URI.create("function://billing/tax")))
                .isTrue();
        assertThat(resource.supports(URI.create("scheduler://webhook")))
                .isFalse();
    }

    @Test
    void parsesExplicitVersion() {
        var input = objectMapper.createObjectNode().put("amount", 10);
        var output = objectMapper.createObjectNode().put("tax", 1.8);
        when(invocationService.invokeForTask(
                "billing", "tax", 3, input, TaskExecutionContext.NONE))
                .thenReturn(output);

        assertThat(resource.execute(URI.create("function://billing/tax@v3"), input))
                .isEqualTo(output);
        verify(invocationService).invokeForTask(
                "billing", "tax", 3, input, TaskExecutionContext.NONE);
    }

    @Test
    void latestVersionUsesActiveVersion() {
        var input = objectMapper.createObjectNode();
        when(invocationService.invokeForTask(
                "billing", "tax", null, input, TaskExecutionContext.NONE))
                .thenReturn(objectMapper.createObjectNode());

        resource.execute(URI.create("function://billing/tax@latest"), input);

        verify(invocationService).invokeForTask(
                "billing", "tax", null, input, TaskExecutionContext.NONE);
    }

    @Test
    void passesWorkflowContextToInvocation() {
        var input = objectMapper.createObjectNode();
        var context = new TaskExecutionContext(java.util.UUID.randomUUID(), "CalculateTax");
        when(invocationService.invokeForTask("billing", "tax", null, input, context))
                .thenReturn(objectMapper.createObjectNode());

        resource.execute(URI.create("function://billing/tax@latest"), input, context);

        verify(invocationService).invokeForTask("billing", "tax", null, input, context);
    }

    @Test
    void rejectsInvalidVersion() {
        assertThatThrownBy(() -> resource.execute(
                URI.create("function://billing/tax@beta"),
                objectMapper.createObjectNode()
        ))
                .isInstanceOf(TaskResourceException.class)
                .extracting(error -> ((TaskResourceException) error).error())
                .isEqualTo(TaskResourceErrors.TASK_FAILED);
    }
}
