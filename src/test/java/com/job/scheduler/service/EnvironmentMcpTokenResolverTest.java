package com.job.scheduler.service;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentMcpTokenResolverTest {

    private final MockEnvironment environment = new MockEnvironment();
    private final EnvironmentMcpTokenResolver resolver = new EnvironmentMcpTokenResolver(environment);

    @Test
    void returnsEmptyForNonBearerServer() {
        assertThat(resolver.resolve(server(McpAuthType.NONE, null))).isEmpty();
    }

    @Test
    void resolvesInlineTokenFromEnvironment() {
        environment.setProperty("scheduler.mcp.tokens.GITHUB_MCP_TOKEN", "  ghp_inline  ");

        assertThat(resolver.resolve(server(McpAuthType.BEARER_TOKEN, "GITHUB_MCP_TOKEN")))
                .contains("ghp_inline");
    }

    @Test
    void resolvesTokenFromMountedFile(@TempDir Path tempDir) throws IOException {
        Path tokenFile = tempDir.resolve("github-token");
        Files.writeString(tokenFile, "ghp_from_file\n");
        environment.setProperty("scheduler.mcp.token-files.GITHUB_MCP_TOKEN", tokenFile.toString());

        assertThat(resolver.resolve(server(McpAuthType.BEARER_TOKEN, "GITHUB_MCP_TOKEN")))
                .contains("ghp_from_file");
    }

    @Test
    void fileTokenTakesPrecedenceOverInlineToken(@TempDir Path tempDir) throws IOException {
        Path tokenFile = tempDir.resolve("github-token");
        Files.writeString(tokenFile, "ghp_from_file");
        environment.setProperty("scheduler.mcp.tokens.GITHUB_MCP_TOKEN", "ghp_inline");
        environment.setProperty("scheduler.mcp.token-files.GITHUB_MCP_TOKEN", tokenFile.toString());

        assertThat(resolver.resolve(server(McpAuthType.BEARER_TOKEN, "GITHUB_MCP_TOKEN")))
                .contains("ghp_from_file");
    }

    @Test
    void returnsEmptyWhenNoTokenConfigured() {
        assertThat(resolver.resolve(server(McpAuthType.BEARER_TOKEN, "GITHUB_MCP_TOKEN"))).isEmpty();
    }

    @Test
    void rejectsBearerServerWithoutRef() {
        assertThatThrownBy(() -> resolver.resolve(server(McpAuthType.BEARER_TOKEN, "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no authTokenRef");
    }

    @Test
    void rejectsUnreadableTokenFile() {
        environment.setProperty("scheduler.mcp.token-files.GITHUB_MCP_TOKEN", "/does/not/exist/token");

        assertThatThrownBy(() -> resolver.resolve(server(McpAuthType.BEARER_TOKEN, "GITHUB_MCP_TOKEN")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not read MCP token file");
    }

    @Test
    void rejectsEmptyTokenFile(@TempDir Path tempDir) throws IOException {
        Path tokenFile = tempDir.resolve("empty-token");
        Files.writeString(tokenFile, "   \n");
        environment.setProperty("scheduler.mcp.token-files.GITHUB_MCP_TOKEN", tokenFile.toString());

        assertThatThrownBy(() -> resolver.resolve(server(McpAuthType.BEARER_TOKEN, "GITHUB_MCP_TOKEN")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is empty");
    }

    private McpServer server(McpAuthType authType, String authTokenRef) {
        McpServer server = new McpServer();
        server.setServerId("local-tools");
        server.setDisplayName("Local Tools");
        server.setBaseUrl("http://localhost:8081");
        server.setEndpoint("/mcp");
        server.setTransport(McpTransport.HTTP);
        server.setAuthType(authType);
        server.setAuthTokenRef(authTokenRef);
        server.setTrustLevel(McpTrustLevel.READ_ONLY);
        server.setStatus(McpServerStatus.ENABLED);
        return server;
    }
}
