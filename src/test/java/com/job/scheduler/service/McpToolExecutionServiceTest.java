package com.job.scheduler.service;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.entity.McpToolExecution;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpToolExecutionStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.repository.McpToolExecutionRepository;
import com.job.scheduler.repository.McpToolRepository;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class McpToolExecutionServiceTest {
    @Mock
    private McpServerRepository mcpServerRepository;

    @Mock
    private McpToolRepository mcpToolRepository;

    @Mock
    private McpToolExecutionRepository mcpToolExecutionRepository;

    @Mock
    private McpClientService mcpClientService;

    private ObjectMapper objectMapper;
    private McpToolExecutionService mcpToolExecutionService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mcpToolExecutionService = new McpToolExecutionService(
                mcpServerRepository,
                mcpToolRepository,
                mcpToolExecutionRepository,
                mcpClientService,
                objectMapper
        );
        lenient().when(mcpToolExecutionRepository.save(any())).thenAnswer(invocation ->
                copyExecution(invocation.getArgument(0))
        );
    }

    @Test
    void callToolAllowsKnownEnabledReadOnlyToolByDefault() {
        McpServer server = server(McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        McpTool tool = tool(server, true);

        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndToolName(server, "ping")).thenReturn(Optional.of(tool));
        when(mcpClientService.callTool("local-tools", "ping", Map.of("message", "hello")))
                .thenReturn(Mono.just(new McpSchema.CallToolResult(List.of(), false, Map.of("ok", true), Map.of())));

        McpSchema.CallToolResult result = mcpToolExecutionService
                .callTool("local-tools", "ping", Map.of("message", "hello"), null)
                .block();

        assertThat(result).isNotNull();
        assertThat(result.structuredContent()).isEqualTo(Map.of("ok", true));
        verify(mcpToolExecutionRepository).save(org.mockito.ArgumentMatchers.argThat(execution ->
                execution.getStatus() == McpToolExecutionStatus.RUNNING
        ));
        verify(mcpToolExecutionRepository).save(org.mockito.ArgumentMatchers.argThat(execution ->
                execution.getStatus() == McpToolExecutionStatus.SUCCESS
                        && execution.getCompletedAt() != null
                        && execution.getResult() != null
        ));
    }

    @Test
    void callToolRejectsMissingServer() {
        when(mcpServerRepository.findByServerId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mcpToolExecutionService.callTool("missing", "ping", Map.of(), null).block())
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("MCP server does not exist");

        verify(mcpClientService, never()).callTool("missing", "ping", Map.of());
    }

    @Test
    void callToolRejectsDisabledServer() {
        McpServer server = server(McpServerStatus.DISABLED, McpTrustLevel.READ_ONLY);
        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndToolName(server, "ping")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mcpToolExecutionService.callTool("local-tools", "ping", Map.of(), null).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP server is disabled: local-tools");
        verify(mcpToolExecutionRepository).save(org.mockito.ArgumentMatchers.argThat(execution ->
                execution.getStatus() == McpToolExecutionStatus.REJECTED
                        && execution.getErrorMessage().equals("MCP server is disabled: local-tools")
        ));
    }

    @Test
    void callToolRejectsUntrustedServer() {
        McpServer server = server(McpServerStatus.ENABLED, McpTrustLevel.UNTRUSTED);
        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndToolName(server, "ping")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mcpToolExecutionService.callTool("local-tools", "ping", Map.of(), null).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP server is untrusted: local-tools");
    }

    @Test
    void callToolRejectsUnknownTool() {
        McpServer server = server(McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndToolName(server, "missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mcpToolExecutionService.callTool("local-tools", "missing", Map.of(), null).block())
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("MCP tool does not exist");
        verify(mcpToolExecutionRepository).save(org.mockito.ArgumentMatchers.argThat(execution ->
                execution.getStatus() == McpToolExecutionStatus.REJECTED
                        && execution.getToolName().equals("missing")
        ));
    }

    @Test
    void callToolRejectsDisabledTool() {
        McpServer server = server(McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        McpTool tool = tool(server, false);

        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndToolName(server, "ping")).thenReturn(Optional.of(tool));

        assertThatThrownBy(() -> mcpToolExecutionService.callTool("local-tools", "ping", Map.of(), null).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP tool is disabled: ping");
    }

    @Test
    void callToolRejectsWriteServerUnlessCallerAllowsWrite() {
        McpServer server = server(McpServerStatus.ENABLED, McpTrustLevel.WRITE);
        McpTool tool = tool(server, true);

        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndToolName(server, "ping")).thenReturn(Optional.of(tool));

        assertThatThrownBy(() -> mcpToolExecutionService.callTool("local-tools", "ping", Map.of(), null).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP server trust level WRITE exceeds allowed level READ_ONLY");
    }

    @Test
    void callToolAllowsWriteServerWhenCallerAllowsWrite() {
        McpServer server = server(McpServerStatus.ENABLED, McpTrustLevel.WRITE);
        McpTool tool = tool(server, true);

        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndToolName(server, "ping")).thenReturn(Optional.of(tool));
        when(mcpClientService.callTool("local-tools", "ping", Map.of()))
                .thenReturn(Mono.just(new McpSchema.CallToolResult(List.of(), false, Map.of(), Map.of())));

        McpSchema.CallToolResult result = mcpToolExecutionService
                .callTool("local-tools", "ping", Map.of(), McpTrustLevel.WRITE)
                .block();

        assertThat(result).isNotNull();
    }

    @Test
    void callToolRejectsArgumentsThatDoNotMatchInputSchema() {
        McpServer server = server(McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        McpTool tool = tool(server, true);
        tool.setInputSchema("""
                {
                  "type": "object",
                  "required": ["message"],
                  "properties": {
                    "message": {
                      "type": "string"
                    }
                  },
                  "additionalProperties": false
                }
                """);

        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndToolName(server, "ping")).thenReturn(Optional.of(tool));

        assertThatThrownBy(() -> mcpToolExecutionService
                .callTool("local-tools", "ping", Map.of("message", 123), null)
                .block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP tool arguments do not match input schema");

        verify(mcpClientService, never()).callTool("local-tools", "ping", Map.of("message", 123));
        verify(mcpToolExecutionRepository).save(org.mockito.ArgumentMatchers.argThat(execution ->
                execution.getStatus() == McpToolExecutionStatus.REJECTED
                        && execution.getErrorMessage().contains("MCP tool arguments do not match input schema")
        ));
    }

    @Test
    void callToolAllowsArgumentsThatMatchInputSchema() {
        McpServer server = server(McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        McpTool tool = tool(server, true);
        tool.setInputSchema("""
                {
                  "type": "object",
                  "required": ["message"],
                  "properties": {
                    "message": {
                      "type": "string"
                    }
                  },
                  "additionalProperties": false
                }
                """);
        Map<String, Object> arguments = Map.of("message", "hello");

        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndToolName(server, "ping")).thenReturn(Optional.of(tool));
        when(mcpClientService.callTool("local-tools", "ping", arguments))
                .thenReturn(Mono.just(new McpSchema.CallToolResult(List.of(), false, Map.of("ok", true), Map.of())));

        McpSchema.CallToolResult result = mcpToolExecutionService
                .callTool("local-tools", "ping", arguments, null)
                .block();

        assertThat(result).isNotNull();
        assertThat(result.structuredContent()).isEqualTo(Map.of("ok", true));
    }

    @Test
    void callToolStoresFailedExecutionWhenMcpClientFails() {
        McpServer server = server(McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        McpTool tool = tool(server, true);

        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndToolName(server, "ping")).thenReturn(Optional.of(tool));
        when(mcpClientService.callTool("local-tools", "ping", Map.of()))
                .thenReturn(Mono.error(new RuntimeException("remote failed")));

        assertThatThrownBy(() -> mcpToolExecutionService
                .callTool("local-tools", "ping", Map.of(), null)
                .block())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("remote failed");

        verify(mcpToolExecutionRepository).save(org.mockito.ArgumentMatchers.argThat(execution ->
                execution.getStatus() == McpToolExecutionStatus.FAILED
                        && execution.getErrorMessage().equals("remote failed")
                        && execution.getCompletedAt() != null
        ));
    }

    private McpServer server(McpServerStatus status, McpTrustLevel trustLevel) {
        McpServer server = new McpServer();
        server.setServerId("local-tools");
        server.setDisplayName("Local Tools");
        server.setBaseUrl("http://localhost:8081");
        server.setEndpoint("/mcp");
        server.setTransport(McpTransport.HTTP);
        server.setAuthType(McpAuthType.NONE);
        server.setTrustLevel(trustLevel);
        server.setStatus(status);
        return server;
    }

    private McpTool tool(McpServer server, boolean enabled) {
        McpTool tool = new McpTool();
        tool.setMcpServer(server);
        tool.setToolName("ping");
        tool.setEnabled(enabled);
        tool.setInputSchema("{\"type\":\"object\"}");
        return tool;
    }

    private McpToolExecution copyExecution(McpToolExecution execution) {
        McpToolExecution copy = new McpToolExecution();
        copy.setId(execution.getId());
        copy.setMcpServer(execution.getMcpServer());
        copy.setMcpTool(execution.getMcpTool());
        copy.setServerId(execution.getServerId());
        copy.setToolName(execution.getToolName());
        copy.setArguments(execution.getArguments());
        copy.setResult(execution.getResult());
        copy.setStatus(execution.getStatus());
        copy.setMaxAllowedTrustLevel(execution.getMaxAllowedTrustLevel());
        copy.setErrorMessage(execution.getErrorMessage());
        copy.setStartedAt(execution.getStartedAt());
        copy.setCompletedAt(execution.getCompletedAt());
        copy.setDurationMs(execution.getDurationMs());
        copy.setCreatedAt(execution.getCreatedAt());
        copy.setUpdatedAt(execution.getUpdatedAt());
        return copy;
    }
}
