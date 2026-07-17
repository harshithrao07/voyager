package com.job.scheduler.service;

import com.job.scheduler.dto.McpServerRequestDTO;
import com.job.scheduler.dto.McpServerResponseDTO;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.repository.McpServerRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServerRegistryServiceTest {
    @Mock
    private McpServerRepository mcpServerRepository;

    private McpServerRegistryService mcpServerRegistryService;

    @BeforeEach
    void setUp() {
        mcpServerRegistryService = new McpServerRegistryService(mcpServerRepository);
    }

    @Test
    void registerServerDefaultsToDisabledAndUntrusted() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "local-tools",
                "Local Tools",
                "http://localhost:8081/",
                "/mcp",
                null,
                null,
                null,
                null,
                McpTransport.HTTP,
                McpAuthType.NONE,
                null,
                null,
                null,
                null,
                null,
                5000
        );

        when(mcpServerRepository.existsByServerId("local-tools")).thenReturn(false);
        when(mcpServerRepository.save(any(McpServer.class))).thenAnswer(invocation -> {
            McpServer server = invocation.getArgument(0);
            server.setId(UUID.randomUUID());
            server.setCreatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            server.setUpdatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            return server;
        });

        McpServerResponseDTO response = mcpServerRegistryService.registerServer(request);

        assertThat(response.serverId()).isEqualTo("local-tools");
        assertThat(response.baseUrl()).isEqualTo("http://localhost:8081");
        assertThat(response.trustLevel()).isEqualTo(McpTrustLevel.UNTRUSTED);
        assertThat(response.status()).isEqualTo(McpServerStatus.DISABLED);
        assertThat(response.requestTimeoutMs()).isEqualTo(5000);

        ArgumentCaptor<McpServer> serverCaptor = ArgumentCaptor.forClass(McpServer.class);
        verify(mcpServerRepository).save(serverCaptor.capture());

        McpServer savedServer = serverCaptor.getValue();
        assertThat(savedServer.getServerId()).isEqualTo("local-tools");
        assertThat(savedServer.getTrustLevel()).isEqualTo(McpTrustLevel.UNTRUSTED);
        assertThat(savedServer.getStatus()).isEqualTo(McpServerStatus.DISABLED);
        assertThat(savedServer.getRequestTimeoutMs()).isEqualTo(5000);
    }

    @Test
    void registerServerRejectsDuplicateServerId() {
        McpServerRequestDTO request = validRequest("local-tools");
        when(mcpServerRepository.existsByServerId("local-tools")).thenReturn(true);

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP server already exists: local-tools");
    }

    @Test
    void registerServerRequiresTokenReferenceForBearerAuth() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "github-tools",
                "GitHub Tools",
                "https://mcp.example.com",
                "/mcp",
                null,
                null,
                null,
                null,
                McpTransport.HTTP,
                McpAuthType.BEARER_TOKEN,
                null,
                null,
                null,
                McpTrustLevel.READ_ONLY,
                McpServerStatus.DISABLED,
                null
        );

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authTokenRef is required for BEARER_TOKEN auth");
    }

    @Test
    void registersStdioServerWithCommandArgsAndEnv() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "local-fs", "Local FS", null, null,
                "npx", List.of("-y", "@modelcontextprotocol/server-filesystem", "/data"),
                java.util.Map.of("LOG_LEVEL", "info"), null,
                McpTransport.STDIO, McpAuthType.NONE, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null
        );
        when(mcpServerRepository.existsByServerId("local-fs")).thenReturn(false);
        when(mcpServerRepository.save(any(McpServer.class))).thenAnswer(invocation -> {
            McpServer server = invocation.getArgument(0);
            server.setId(UUID.randomUUID());
            server.setCreatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            server.setUpdatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            return server;
        });

        McpServerResponseDTO response = mcpServerRegistryService.registerServer(request);

        assertThat(response.transport()).isEqualTo(McpTransport.STDIO);
        assertThat(response.command()).isEqualTo("npx");
        assertThat(response.args())
                .containsExactly("-y", "@modelcontextprotocol/server-filesystem", "/data");
        assertThat(response.env()).containsEntry("LOG_LEVEL", "info");
    }

    @Test
    void rejectsRawSecretInStdioEnvironment() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "local-github", "Local GitHub", null, null,
                "npx", List.of("github-mcp"),
                java.util.Map.of("GITHUB_TOKEN", "ghp_plaintext"), null,
                McpTransport.STDIO, McpAuthType.NONE, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null
        );

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GITHUB_TOKEN")
                .hasMessageContaining("must use ${secret:REF} or ref:REF");
    }

    @Test
    void storesOnlySecretReferenceMarkerInStdioEnvironment() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "local-github", "Local GitHub", null, null,
                "npx", List.of("github-mcp"),
                java.util.Map.of("GITHUB_TOKEN", "${secret:MCP_GITHUB_TOKEN}"), null,
                McpTransport.STDIO, McpAuthType.NONE, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null
        );
        when(mcpServerRepository.existsByServerId("local-github")).thenReturn(false);
        when(mcpServerRepository.save(any(McpServer.class))).thenAnswer(invocation -> {
            McpServer server = invocation.getArgument(0);
            server.setId(UUID.randomUUID());
            server.setCreatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            server.setUpdatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            return server;
        });

        McpServerResponseDTO response = mcpServerRegistryService.registerServer(request);

        assertThat(response.env())
                .containsEntry("GITHUB_TOKEN", "${secret:MCP_GITHUB_TOKEN}");
    }

    @Test
    void rejectsStdioServerWithoutCommand() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "local-fs", "Local FS", null, null,
                null, null, null, null,
                McpTransport.STDIO, McpAuthType.NONE, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null
        );

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command is required for STDIO transport");
    }

    @Test
    void rejectsStdioBearerWithoutAuthEnvVar() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "gh", "GitHub", null, null,
                "gh-mcp", null, null, null,
                McpTransport.STDIO, McpAuthType.BEARER_TOKEN, "GH_TOKEN_REF", null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null
        );

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authEnvVar is required for STDIO BEARER_TOKEN auth");
    }

    @Test
    void rejectsHttpServerWithoutBaseUrl() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "remote", "Remote", null, "/mcp",
                null, null, null, null,
                McpTransport.HTTP, McpAuthType.NONE, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null
        );

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl is required for HTTP transport");
    }

    @Test
    void rejectsApiKeyAuthWithoutHeaderName() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "kv", "KV", "https://mcp.example.com", "/mcp",
                null, null, null, null,
                McpTransport.HTTP, McpAuthType.API_KEY, "KV_KEY_REF",
                null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null
        );

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authHeaderName is required for API_KEY auth");
    }

    @Test
    void rejectsBasicAuthWithoutUsername() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "basic", "Basic", "https://mcp.example.com", "/mcp",
                null, null, null, null,
                McpTransport.HTTP, McpAuthType.BASIC, "BASIC_PW_REF",
                null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null
        );

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authUsername is required for BASIC auth");
    }

    @Test
    void rejectsStdioWithApiKeyAuth() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "cli", "CLI", null, null,
                "mcp-cli", null, null, null,
                McpTransport.STDIO, McpAuthType.API_KEY, "REF",
                "X-API-Key", null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null
        );

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("STDIO transport supports only NONE or BEARER_TOKEN auth");
    }

    @Test
    void registersApiKeyServerWithHeaderName() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "kv", "KV", "https://mcp.example.com", "/mcp",
                null, null, null, null,
                McpTransport.HTTP, McpAuthType.API_KEY, "KV_KEY_REF",
                "X-API-Key", null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null
        );
        when(mcpServerRepository.existsByServerId("kv")).thenReturn(false);
        when(mcpServerRepository.save(any(McpServer.class))).thenAnswer(invocation -> {
            McpServer server = invocation.getArgument(0);
            server.setId(UUID.randomUUID());
            server.setCreatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            server.setUpdatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            return server;
        });

        McpServerResponseDTO response = mcpServerRegistryService.registerServer(request);

        assertThat(response.authType()).isEqualTo(McpAuthType.API_KEY);
        assertThat(response.authHeaderName()).isEqualTo("X-API-Key");
    }

    @Test
    void getServersCanFilterByStatus() {
        McpServer enabledServer = server("enabled-tools", McpServerStatus.ENABLED);
        when(mcpServerRepository.findByStatusOrderByCreatedAtDesc(McpServerStatus.ENABLED))
                .thenReturn(List.of(enabledServer));

        List<McpServerResponseDTO> result = mcpServerRegistryService.getServers(McpServerStatus.ENABLED);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).serverId()).isEqualTo("enabled-tools");
    }

    @Test
    void updateStatusChangesOnlyStatus() {
        McpServer server = server("local-tools", McpServerStatus.DISABLED);
        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpServerRepository.save(any(McpServer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        McpServerResponseDTO response = mcpServerRegistryService.updateStatus("local-tools", McpServerStatus.ENABLED);

        assertThat(response.status()).isEqualTo(McpServerStatus.ENABLED);
        verify(mcpServerRepository).save(server);
    }

    @Test
    void getServerThrowsWhenMissing() {
        when(mcpServerRepository.findByServerId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mcpServerRegistryService.getServer("missing"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("MCP server does not exist");
    }

    private McpServerRequestDTO validRequest(String serverId) {
        return new McpServerRequestDTO(
                serverId,
                "Local Tools",
                "http://localhost:8081",
                "/mcp",
                null,
                null,
                null,
                null,
                McpTransport.HTTP,
                McpAuthType.NONE,
                null,
                null,
                null,
                McpTrustLevel.READ_ONLY,
                McpServerStatus.ENABLED,
                null
        );
    }

    private McpServer server(String serverId, McpServerStatus status) {
        McpServer server = new McpServer();
        server.setId(UUID.randomUUID());
        server.setServerId(serverId);
        server.setDisplayName("Local Tools");
        server.setBaseUrl("http://localhost:8081");
        server.setEndpoint("/mcp");
        server.setTransport(McpTransport.HTTP);
        server.setAuthType(McpAuthType.NONE);
        server.setTrustLevel(McpTrustLevel.READ_ONLY);
        server.setStatus(status);
        server.setCreatedAt(Instant.parse("2026-06-17T00:00:00Z"));
        server.setUpdatedAt(Instant.parse("2026-06-17T00:00:00Z"));
        return server;
    }
}
