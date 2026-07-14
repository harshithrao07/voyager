package com.job.scheduler.workflow.task;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.dto.payload.McpToolPayload;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.handlers.McpToolHandler;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpTaskResourceTest {
    @Mock
    private McpToolHandler mcpToolHandler;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI uri = URI.create("voyager://mcp/crm/get-customer");

    private McpTaskResource resource() {
        return new McpTaskResource(mcpToolHandler);
    }

    @Test
    void supportsVoyagerMcpResourcesOnly() {
        assertThat(resource().supports(uri)).isTrue();
        assertThat(resource().supports(URI.create("voyager://function/tax")))
                .isFalse();
        assertThat(resource().supports(URI.create("voyager://system/webhook")))
                .isFalse();
    }

    @Test
    void invokesToolWithReadOnlyTrustByDefaultAndReturnsOutput() {
        var out = objectMapper.createObjectNode().put("name", "Ada");
        when(mcpToolHandler.handle(any())).thenReturn(new StepResult(out));

        assertThat(resource().execute(uri, objectMapper.createObjectNode()))
                .isEqualTo(out);
        assertThat(capturePayload().maxAllowedTrustLevel())
                .isEqualTo(McpTrustLevel.READ_ONLY);
    }

    @Test
    void grantsDeclaredTrustLevelFromQueryParam() {
        when(mcpToolHandler.handle(any())).thenReturn(StepResult.empty());

        resource().execute(
                URI.create("voyager://mcp/crm/create-lead?trust=WRITE"),
                objectMapper.createObjectNode());

        assertThat(capturePayload().maxAllowedTrustLevel())
                .isEqualTo(McpTrustLevel.WRITE);
    }

    @Test
    void parsesDeclaredTrustLevelCaseInsensitively() {
        when(mcpToolHandler.handle(any())).thenReturn(StepResult.empty());

        resource().execute(
                URI.create("voyager://mcp/crm/purge?trust=destructive"),
                objectMapper.createObjectNode());

        assertThat(capturePayload().maxAllowedTrustLevel())
                .isEqualTo(McpTrustLevel.DESTRUCTIVE);
    }

    @Test
    void rejectsUnknownTrustLevelWithoutCallingHandler() {
        assertThatThrownBy(() -> resource().execute(
                URI.create("voyager://mcp/crm/get-customer?trust=bogus"),
                objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.TASK_FAILED);

        verify(mcpToolHandler, never()).handle(any());
    }

    @Test
    void rejectsUntrustedTrustLevelWithoutCallingHandler() {
        assertThatThrownBy(() -> resource().execute(
                URI.create("voyager://mcp/crm/get-customer?trust=UNTRUSTED"),
                objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.TASK_FAILED);

        verify(mcpToolHandler, never()).handle(any());
    }

    @Test
    void treatsWriteAndDestructiveMcpResourcesAsMutating() {
        assertThat(McpTaskResource.isMutatingResource("voyager://mcp/crm/create-lead?trust=WRITE"))
                .isTrue();
        assertThat(McpTaskResource.isMutatingResource("voyager://mcp/crm/purge?trust=destructive"))
                .isTrue();
    }

    @Test
    void treatsReadOnlyAndNonMcpResourcesAsNonMutating() {
        assertThat(McpTaskResource.isMutatingResource("voyager://mcp/crm/get?trust=READ_ONLY"))
                .isFalse();
        assertThat(McpTaskResource.isMutatingResource("voyager://mcp/crm/get")).isFalse();
        assertThat(McpTaskResource.isMutatingResource("voyager://function/tax@v2")).isFalse();
        assertThat(McpTaskResource.isMutatingResource("voyager://system/webhook")).isFalse();
    }

    @Test
    void treatsUnparseableOrMalformedTrustAsNonMutating() {
        // Malformed trust fails at execution before any side effect, so retrying is safe.
        assertThat(McpTaskResource.isMutatingResource("voyager://mcp/crm/x?trust=bogus"))
                .isFalse();
        assertThat(McpTaskResource.isMutatingResource(null)).isFalse();
        assertThat(McpTaskResource.isMutatingResource("")).isFalse();
    }

    private McpToolPayload capturePayload() {
        ArgumentCaptor<McpToolPayload> captor = ArgumentCaptor.forClass(McpToolPayload.class);
        verify(mcpToolHandler).handle(captor.capture());
        return captor.getValue();
    }

    @Test
    void rejectsMalformedResource() {
        assertThatThrownBy(() -> resource().execute(
                URI.create("voyager://mcp/only-server"),
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
