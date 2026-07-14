package com.job.scheduler.service;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.exception.McpConnectionException;
import com.job.scheduler.repository.McpServerRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpClientServiceTest {
    @Mock
    private McpServerRepository mcpServerRepository;

    @Mock
    private McpTokenResolver tokenResolver;

    private McpClientService mcpClientService;

    @BeforeEach
    void setUp() {
        mcpClientService = new McpClientService(mcpServerRepository, tokenResolver);
    }

    @Test
    void listToolsRejectsMissingServer() {
        when(mcpServerRepository.findByServerId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mcpClientService.listTools("missing").block())
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("MCP server does not exist");
    }

    @Test
    void listToolsRejectsDisabledServerBeforeConnecting() {
        when(mcpServerRepository.findByServerId("local-tools"))
                .thenReturn(Optional.of(server(McpServerStatus.DISABLED, McpAuthType.NONE)));

        assertThatThrownBy(() -> mcpClientService.listTools("local-tools").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP server is disabled: local-tools");
    }

    @Test
    void listToolsRejectsBearerTokenServerWhenNoTokenConfigured() {
        when(mcpServerRepository.findByServerId("local-tools"))
                .thenReturn(Optional.of(server(McpServerStatus.ENABLED, McpAuthType.BEARER_TOKEN)));
        when(tokenResolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mcpClientService.listTools("local-tools").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No token configured for MCP server: local-tools");
    }

    @Test
    void surfacesClearErrorWhenStdioProcessCannotLaunch() {
        when(mcpServerRepository.findByServerId("local-cli"))
                .thenReturn(Optional.of(stdioServer("voyager-no-such-command-zzz")));

        assertThatThrownBy(() -> mcpClientService.listTools("local-cli").block())
                .isInstanceOf(McpConnectionException.class)
                .hasMessageContaining("Could not launch MCP process 'voyager-no-such-command-zzz'")
                .hasMessageContaining("use HTTP transport or install the runtime");
    }

    private McpServer stdioServer(String command) {
        McpServer server = new McpServer();
        server.setServerId("local-cli");
        server.setDisplayName("Local CLI");
        server.setTransport(McpTransport.STDIO);
        server.setCommand(command);
        server.setArgs(java.util.List.of());
        server.setEnv(java.util.Map.of());
        server.setAuthType(McpAuthType.NONE);
        server.setTrustLevel(McpTrustLevel.READ_ONLY);
        server.setStatus(McpServerStatus.ENABLED);
        return server;
    }

    private McpServer server(McpServerStatus status, McpAuthType authType) {
        McpServer server = new McpServer();
        server.setServerId("local-tools");
        server.setDisplayName("Local Tools");
        server.setBaseUrl("http://localhost:8081");
        server.setEndpoint("/mcp");
        server.setTransport(McpTransport.HTTP);
        server.setAuthType(authType);
        server.setAuthTokenRef(authType == McpAuthType.BEARER_TOKEN ? "secret/local-tools/token" : null);
        server.setTrustLevel(McpTrustLevel.READ_ONLY);
        server.setStatus(status);
        return server;
    }
}
