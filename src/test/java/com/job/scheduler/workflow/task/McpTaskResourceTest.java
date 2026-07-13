package com.job.scheduler.workflow.task;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.handlers.McpToolHandler;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpTaskResourceTest {
    @Mock
    private McpToolHandler mcpToolHandler;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI uri = URI.create("mcp://crm/get-customer");

    private McpTaskResource resource() {
        return new McpTaskResource(mcpToolHandler);
    }

    @Test
    void supportsMcpSchemeOnly() {
        assertThat(resource().supports(uri)).isTrue();
        assertThat(resource().supports(URI.create("voyager://webhook")))
                .isFalse();
    }

    @Test
    void invokesToolWithReadOnlyTrustAndReturnsOutput() {
        var out = objectMapper.createObjectNode().put("name", "Ada");
        when(mcpToolHandler.handle(any())).thenReturn(new StepResult(out));

        assertThat(resource().execute(uri, objectMapper.createObjectNode()))
                .isEqualTo(out);
    }

    @Test
    void rejectsMalformedResource() {
        assertThatThrownBy(() -> resource().execute(
                URI.create("mcp:/missing-server"),
                objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.TASK_FAILED);
    }

    @Test
    void mapsTrustRejectionToPermissions() {
        when(mcpToolHandler.handle(any())).thenThrow(new IllegalStateException(
                "MCP server trust level WRITE exceeds allowed level READ_ONLY"));

        assertThatThrownBy(() ->
                resource().execute(uri, objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.PERMISSIONS);
    }

    @Test
    void mapsUntrustedServerToPermissions() {
        when(mcpToolHandler.handle(any()))
                .thenThrow(new IllegalStateException(
                        "MCP server is untrusted: crm"));

        assertThatThrownBy(() ->
                resource().execute(uri, objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.PERMISSIONS);
    }

    @Test
    void mapsMissingToolToNotFound() {
        when(mcpToolHandler.handle(any()))
                .thenThrow(new EntityNotFoundException("MCP tool not found"));

        assertThatThrownBy(() ->
                resource().execute(uri, objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.MCP_TOOL_NOT_FOUND);
    }

    @Test
    void mapsGenericFailureToToolFailed() {
        when(mcpToolHandler.handle(any()))
                .thenThrow(new IllegalStateException("MCP tool is disabled: x"));

        assertThatThrownBy(() ->
                resource().execute(uri, objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.MCP_TOOL_FAILED);
    }
}
