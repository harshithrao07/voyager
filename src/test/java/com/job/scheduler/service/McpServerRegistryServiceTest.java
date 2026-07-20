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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServerRegistryServiceTest {
    private static final String KEY = "xYZP4KD/T0APHH/9GMLiO9vt8D/GFCJXwLzh5ALiGV0=";

    @Mock
    private McpServerRepository mcpServerRepository;

    private final SecretCipher cipher = new SecretCipher(KEY);
    private McpServerRegistryService mcpServerRegistryService;

    @BeforeEach
    void setUp() {
        mcpServerRegistryService = new McpServerRegistryService(mcpServerRepository, cipher);
    }

    private void stubSave() {
        when(mcpServerRepository.save(any(McpServer.class))).thenAnswer(invocation -> {
            McpServer server = invocation.getArgument(0);
            server.setId(UUID.randomUUID());
            server.setCreatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            server.setUpdatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            return server;
        });
    }

    @Test
    void registerServerDefaultsToDisabledAndUntrusted() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "local-tools", "Local Tools", "http://localhost:8081/", "/mcp",
                null, null, null, null, null, null,
                McpTransport.HTTP, McpAuthType.NONE, null, null, null,
                null, null, 5000);

        when(mcpServerRepository.existsByServerId("local-tools")).thenReturn(false);
        stubSave();

        McpServerResponseDTO response = mcpServerRegistryService.registerServer(request);

        assertThat(response.serverId()).isEqualTo("local-tools");
        assertThat(response.baseUrl()).isEqualTo("http://localhost:8081");
        assertThat(response.trustLevel()).isEqualTo(McpTrustLevel.UNTRUSTED);
        assertThat(response.status()).isEqualTo(McpServerStatus.DISABLED);
        assertThat(response.requestTimeoutMs()).isEqualTo(5000);
        assertThat(response.hasAuthToken()).isFalse();

        ArgumentCaptor<McpServer> serverCaptor = ArgumentCaptor.forClass(McpServer.class);
        verify(mcpServerRepository).save(serverCaptor.capture());
        assertThat(serverCaptor.getValue().getAuthTokenEncrypted()).isNull();
    }

    @Test
    void registerServerRejectsDuplicateServerId() {
        when(mcpServerRepository.existsByServerId("local-tools")).thenReturn(true);

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(validRequest("local-tools")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP server already exists: local-tools");
    }

    @Test
    void registerServerRequiresTokenForBearerAuth() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "github-tools", "GitHub Tools", "https://mcp.example.com", "/mcp",
                null, null, null, null, null, null,
                McpTransport.HTTP, McpAuthType.BEARER_TOKEN, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null);

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authToken is required for BEARER_TOKEN auth");
    }

    @Test
    void registersStdioServerWithCommandArgsAndPlaintextEnv() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "local-fs", "Local FS", null, null,
                "npx", List.of("-y", "@modelcontextprotocol/server-filesystem", "/data"),
                Map.of("LOG_LEVEL", "info"), null, null, null,
                McpTransport.STDIO, McpAuthType.NONE, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null);
        when(mcpServerRepository.existsByServerId("local-fs")).thenReturn(false);
        stubSave();

        McpServerResponseDTO response = mcpServerRegistryService.registerServer(request);

        assertThat(response.transport()).isEqualTo(McpTransport.STDIO);
        assertThat(response.command()).isEqualTo("npx");
        assertThat(response.args())
                .containsExactly("-y", "@modelcontextprotocol/server-filesystem", "/data");
        assertThat(response.env()).containsEntry("LOG_LEVEL", "info");
        assertThat(response.secretEnvKeys()).isEmpty();
    }

    @Test
    void encryptsStdioSecretEnvAndReturnsOnlyKeys() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "local-github", "Local GitHub", null, null,
                "npx", List.of("github-mcp"),
                Map.of("LOG_LEVEL", "info"), Map.of("GITHUB_TOKEN", "ghp_plaintext"), null, null,
                McpTransport.STDIO, McpAuthType.NONE, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null);
        when(mcpServerRepository.existsByServerId("local-github")).thenReturn(false);
        stubSave();

        ArgumentCaptor<McpServer> serverCaptor = ArgumentCaptor.forClass(McpServer.class);
        McpServerResponseDTO response = mcpServerRegistryService.registerServer(request);
        verify(mcpServerRepository).save(serverCaptor.capture());

        // Non-secret env stays plaintext; secret env is encrypted and never returned.
        assertThat(response.env()).containsEntry("LOG_LEVEL", "info");
        assertThat(response.secretEnvKeys()).containsExactly("GITHUB_TOKEN");
        String storedSecret = serverCaptor.getValue().getSecretEnv().get("GITHUB_TOKEN");
        assertThat(storedSecret).startsWith("v1:").doesNotContain("ghp_plaintext");
        assertThat(cipher.decrypt(storedSecret)).isEqualTo("ghp_plaintext");
    }

    @Test
    void encryptsAuthTokenAndReportsHasAuthToken() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "kv", "KV", "https://mcp.example.com", "/mcp",
                null, null, null, null, null, null,
                McpTransport.HTTP, McpAuthType.API_KEY, "secret-key-value",
                "X-API-Key", null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null);
        when(mcpServerRepository.existsByServerId("kv")).thenReturn(false);
        stubSave();

        ArgumentCaptor<McpServer> serverCaptor = ArgumentCaptor.forClass(McpServer.class);
        McpServerResponseDTO response = mcpServerRegistryService.registerServer(request);
        verify(mcpServerRepository).save(serverCaptor.capture());

        assertThat(response.hasAuthToken()).isTrue();
        assertThat(response.authHeaderName()).isEqualTo("X-API-Key");
        String stored = serverCaptor.getValue().getAuthTokenEncrypted();
        assertThat(stored).startsWith("v1:").doesNotContain("secret-key-value");
        assertThat(cipher.decrypt(stored)).isEqualTo("secret-key-value");
    }

    @Test
    void encryptsMultipleCustomHeadersAndReturnsOnlyTheirNames() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "multi-auth", "Multi Auth", "https://mcp.example.com", "/mcp",
                null, null, null, null,
                Map.of("X-API-Key", "key-value", "X-Client-Secret", "client-value"),
                null,
                McpTransport.HTTP, McpAuthType.CUSTOM_HEADERS, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null);
        when(mcpServerRepository.existsByServerId("multi-auth")).thenReturn(false);
        stubSave();

        ArgumentCaptor<McpServer> serverCaptor = ArgumentCaptor.forClass(McpServer.class);
        McpServerResponseDTO response = mcpServerRegistryService.registerServer(request);
        verify(mcpServerRepository).save(serverCaptor.capture());

        assertThat(response.hasAuthToken()).isFalse();
        assertThat(response.secretHeaderNames())
                .containsExactlyInAnyOrder("X-API-Key", "X-Client-Secret");
        Map<String, String> stored = serverCaptor.getValue().getSecretHeaders();
        assertThat(stored.values()).allMatch(value -> value.startsWith("v1:"));
        assertThat(cipher.decrypt(stored.get("X-API-Key"))).isEqualTo("key-value");
        assertThat(cipher.decrypt(stored.get("X-Client-Secret"))).isEqualTo("client-value");
    }

    @Test
    void customHeaderEditKeepsBlankExistingValueAndDropsRemovedNames() {
        McpServer server = server("multi-auth", McpServerStatus.ENABLED);
        server.setAuthType(McpAuthType.CUSTOM_HEADERS);
        String existingCiphertext = cipher.encrypt("old-key");
        server.setSecretHeaders(Map.of(
                "X-API-Key", existingCiphertext,
                "X-Removed", cipher.encrypt("remove-me")
        ));
        when(mcpServerRepository.findByServerId("multi-auth")).thenReturn(Optional.of(server));
        when(mcpServerRepository.save(any(McpServer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        McpServerRequestDTO request = new McpServerRequestDTO(
                "multi-auth", "Multi Auth", "https://mcp.example.com", "/mcp",
                null, null, null, null,
                Map.of("X-API-Key", "", "X-New-Secret", "new-value"),
                null,
                McpTransport.HTTP, McpAuthType.CUSTOM_HEADERS, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null);

        McpServerResponseDTO response = mcpServerRegistryService.updateServer("multi-auth", request);

        assertThat(server.getSecretHeaders()).containsKey("X-API-Key");
        assertThat(server.getSecretHeaders().get("X-API-Key")).isEqualTo(existingCiphertext);
        assertThat(server.getSecretHeaders()).doesNotContainKey("X-Removed");
        assertThat(cipher.decrypt(server.getSecretHeaders().get("X-New-Secret")))
                .isEqualTo("new-value");
        assertThat(response.secretHeaderNames())
                .containsExactlyInAnyOrder("X-API-Key", "X-New-Secret");
    }

    @Test
    void rejectsMissingOrUnsafeCustomHeaders() {
        McpServerRequestDTO missing = new McpServerRequestDTO(
                "multi-auth", "Multi Auth", "https://mcp.example.com", "/mcp",
                null, null, null, null, Map.of(), null,
                McpTransport.HTTP, McpAuthType.CUSTOM_HEADERS, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null);
        McpServerRequestDTO restricted = new McpServerRequestDTO(
                "multi-auth", "Multi Auth", "https://mcp.example.com", "/mcp",
                null, null, null, null, Map.of("Host", "evil.example"), null,
                McpTransport.HTTP, McpAuthType.CUSTOM_HEADERS, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null);

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("At least one header is required for CUSTOM_HEADERS auth");
        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(restricted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("managed by the HTTP transport");
    }

    @Test
    void rejectsStdioServerWithoutCommand() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "local-fs", "Local FS", null, null,
                null, null, null, null, null, null,
                McpTransport.STDIO, McpAuthType.NONE, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null);

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command is required for STDIO transport");
    }

    @Test
    void rejectsStdioBearerWithoutAuthEnvVar() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "gh", "GitHub", null, null,
                "gh-mcp", null, null, null, null, null,
                McpTransport.STDIO, McpAuthType.BEARER_TOKEN, "tok", null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null);

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authEnvVar is required for STDIO BEARER_TOKEN auth");
    }

    @Test
    void rejectsHttpServerWithoutBaseUrl() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "remote", "Remote", null, "/mcp",
                null, null, null, null, null, null,
                McpTransport.HTTP, McpAuthType.NONE, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null);

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl is required for HTTP transport");
    }

    @Test
    void rejectsApiKeyAuthWithoutHeaderName() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "kv", "KV", "https://mcp.example.com", "/mcp",
                null, null, null, null, null, null,
                McpTransport.HTTP, McpAuthType.API_KEY, "tok", null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null);

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authHeaderName is required for API_KEY auth");
    }

    @Test
    void rejectsBasicAuthWithoutUsername() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "basic", "Basic", "https://mcp.example.com", "/mcp",
                null, null, null, null, null, null,
                McpTransport.HTTP, McpAuthType.BASIC, "tok", null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null);

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authUsername is required for BASIC auth");
    }

    @Test
    void rejectsStdioWithApiKeyAuth() {
        McpServerRequestDTO request = new McpServerRequestDTO(
                "cli", "CLI", null, null,
                "mcp-cli", null, null, null, null, null,
                McpTransport.STDIO, McpAuthType.API_KEY, "tok", "X-API-Key", null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null);

        assertThatThrownBy(() -> mcpServerRegistryService.registerServer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("STDIO transport supports only NONE or BEARER_TOKEN auth");
    }

    @Test
    void getServersCanFilterByStatus() {
        when(mcpServerRepository.findByStatusOrderByCreatedAtDesc(McpServerStatus.ENABLED))
                .thenReturn(List.of(server("enabled-tools", McpServerStatus.ENABLED)));

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
                serverId, "Local Tools", "http://localhost:8081", "/mcp",
                null, null, null, null, null, null,
                McpTransport.HTTP, McpAuthType.NONE, null, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.ENABLED, null);
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
