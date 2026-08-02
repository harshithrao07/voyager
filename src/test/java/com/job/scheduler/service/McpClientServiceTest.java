package com.job.scheduler.service;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.exception.McpConnectionException;
import com.job.scheduler.exception.McpRemoteHttpException;
import com.job.scheduler.repository.McpServerRepository;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpClientServiceTest {
    private static final String KEY = "xYZP4KD/T0APHH/9GMLiO9vt8D/GFCJXwLzh5ALiGV0=";

    @Mock
    private McpServerRepository mcpServerRepository;

    private final SecretCipher cipher = new SecretCipher(KEY);
    private McpClientService mcpClientService;

    @BeforeEach
    void setUp() {
        mcpClientService = new McpClientService(mcpServerRepository, cipher);
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
        McpServer server = server(McpServerStatus.ENABLED, McpAuthType.BEARER_TOKEN);
        server.setAuthTokenEncrypted(null); // authenticated type but no stored token
        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));

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
                .hasMessageContaining("command 'voyager-no-such-command-zzz' was not found")
                .hasMessageContaining("use HTTP transport or install the runtime");
    }

    @Test
    void recognizesSdk401AuthorizationFailureForRetry() {
        HttpResponse.ResponseInfo unauthorized = new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return 401;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (name, value) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
        var exception = new McpHttpClientTransportAuthorizationException(
                "unauthorized",
                null,
                unauthorized
        );

        assertThat(mcpClientService.isAuthenticationFailure(exception)).isTrue();
        assertThat(mcpClientService.isAuthenticationFailure(
                new IllegalStateException("other failure")
        )).isFalse();
    }

    @Test
    void preservesRemoteAuthorizationStatusForTheApiResponse() {
        HttpResponse.ResponseInfo unauthorized = responseInfo(401);
        var authorization = new McpHttpClientTransportAuthorizationException(
                "Authorization error when sending message",
                null,
                unauthorized
        );
        McpServer server = server(McpServerStatus.ENABLED, McpAuthType.NONE);

        Throwable mapped = mcpClientService.mapConnectionError(
                server,
                new RuntimeException("Client failed to initialize", authorization)
        );

        assertThat(mapped)
                .isInstanceOf(McpRemoteHttpException.class)
                .hasMessage("MCP server 'local-tools' returned 401 Unauthorized. The server is "
                        + "configured with no authentication; configure the authentication required "
                        + "by the remote MCP server and retry.");
        assertThat(((McpRemoteHttpException) mapped).getStatusCode()).isEqualTo(401);
    }

    private HttpResponse.ResponseInfo responseInfo(int statusCode) {
        return new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return statusCode;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (name, value) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    @Test
    void customHeaderAuthDecryptsAndSeedsEveryConfiguredHeader() {
        McpServer server = server(McpServerStatus.ENABLED, McpAuthType.CUSTOM_HEADERS);
        server.setSecretHeaders(Map.of(
                "X-API-Key", cipher.encrypt("key-value"),
                "X-Client-Secret", cipher.encrypt("client-value")
        ));

        var request = mcpClientService.authHeader(server)
                .uri(URI.create("https://mcp.example.com/mcp"))
                .build();

        assertThat(request.headers().firstValue("X-API-Key")).contains("key-value");
        assertThat(request.headers().firstValue("X-Client-Secret")).contains("client-value");
    }

    @Test
    void customHeaderAuthRequiresAtLeastOneStoredHeader() {
        McpServer server = server(McpServerStatus.ENABLED, McpAuthType.CUSTOM_HEADERS);
        server.setSecretHeaders(Map.of());

        assertThatThrownBy(() -> mcpClientService.authHeader(server))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No custom authentication headers configured for MCP server: local-tools");
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
        server.setAuthTokenEncrypted(
                authType == McpAuthType.BEARER_TOKEN ? cipher.encrypt("local-tools-token") : null);
        server.setTrustLevel(McpTrustLevel.READ_ONLY);
        server.setStatus(status);
        return server;
    }
}
