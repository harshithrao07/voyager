package com.job.scheduler.service;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.exception.McpConnectionException;
import com.job.scheduler.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.File;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);
    private static final String MCP_SERVER_NOT_FOUND_MESSAGE = "MCP server does not exist";

    private final McpServerRepository mcpServerRepository;
    private final SecretCipher secretCipher;

    /** Default per-request timeout when a server does not override it. */
    @Value("${scheduler.mcp.request-timeout-ms:30000}")
    private long defaultRequestTimeoutMs = 30000;

    /** Startup/initialize has its own SDK timeout and is slower for cold npx installs. */
    @Value("${scheduler.mcp.initialization-timeout-ms:60000}")
    private long defaultInitializationTimeoutMs = 60000;

    // One initialized client per server, reused across calls so every invocation
    // no longer pays a fresh connect+initialize+close handshake. An entry is
    // rebuilt when the server's connection config changes (fingerprint) and
    // evicted on any operation error, so a dropped session self-heals next call.
    private final Map<String, PooledClient> pool = new ConcurrentHashMap<>();

    public Mono<McpSchema.ListToolsResult> listTools(String serverId) {
        return withClient(serverId, client -> client.listTools());
    }

    public Mono<McpSchema.CallToolResult> callTool(
            String serverId,
            String toolName,
            Map<String, Object> arguments
    ) {
        Map<String, Object> safeArguments = arguments == null ? Collections.emptyMap() : arguments;
        return withClient(serverId, client ->
                client.callTool(
                        McpSchema.CallToolRequest.builder(toolName)
                                .arguments(safeArguments)
                                .build()
                )
        );
    }

    private <T> Mono<T> withClient(String serverId, Function<McpAsyncClient, Mono<T>> operation) {
        return executeWithClient(serverId, operation, true);
    }

    private <T> Mono<T> executeWithClient(
            String serverId,
            Function<McpAsyncClient, Mono<T>> operation,
            boolean retryAuthenticationFailure
    ) {
        return Mono.defer(() -> {
            McpServer server = findEnabledServer(serverId);
            PooledClient pooled = acquire(serverId, server);
            return pooled.ready()
                    .flatMap(operation)
                    .onErrorResume(error -> {
                        evict(serverId, pooled);
                        if (retryAuthenticationFailure && isAuthenticationFailure(error)) {
                            log.info(
                                    "MCP authentication failed for {}; recreating client and retrying once",
                                    serverId
                            );
                            return executeWithClient(serverId, operation, false);
                        }
                        return Mono.error(mapConnectionError(server, error));
                    });
        });
    }

    boolean isAuthenticationFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpHttpClientTransportAuthorizationException authorization
                    && authorization.getResponseInfo() != null
                    && authorization.getResponseInfo().statusCode() == 401) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * A launch of a resolvable STDIO command that still fails to start or finish
     * the handshake is turned into an actionable {@link McpConnectionException};
     * every other error (and already-mapped ones) passes through unchanged. The
     * common "command not found" case is caught earlier by the pre-flight check.
     */
    private Throwable mapConnectionError(McpServer server, Throwable error) {
        if (error instanceof McpConnectionException) {
            return error;
        }
        if (server.getTransport() == McpTransport.STDIO && isStdioStartupFailure(error)) {
            return new McpConnectionException(
                    "MCP server '" + server.getServerId() + "' (command '" + server.getCommand()
                            + "') failed to start or complete the MCP handshake. Ensure the command "
                            + "runs and speaks MCP over stdio in the backend's environment. Cause: "
                            + rootMessage(error),
                    error);
        }
        return error;
    }

    private boolean isStdioStartupFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("failed to start process")
                        || lower.contains("cannot run program")
                        || lower.contains("failed to initialize")
                        || lower.contains("failed during connect")
                        || lower.contains("no such file")) {
                    return true;
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * Whether {@code command} can be found in the backend's environment — a
     * direct path that exists, or a bare name resolvable on PATH (honoring
     * PATHEXT on Windows).
     */
    private boolean commandResolvable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (command.contains("/") || command.contains("\\")) {
            return Files.exists(Path.of(command));
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return false;
        }
        List<String> names = new ArrayList<>();
        names.add(command);
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (windows && command.indexOf('.') < 0) {
            String pathext = System.getenv("PATHEXT");
            String extensions = (pathext == null || pathext.isBlank()) ? ".COM;.EXE;.BAT;.CMD" : pathext;
            for (String ext : extensions.split(";")) {
                if (!ext.isBlank()) {
                    names.add(command + ext.trim());
                }
            }
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                try {
                    if (Files.exists(Path.of(dir, name))) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // Skip malformed PATH entries.
                }
            }
        }
        return false;
    }

    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    /**
     * Returns the pooled client for a server, (re)building it when there is none
     * or its connection config changed. The remap runs atomically per key and
     * never blocks: {@code buildClient} only assembles objects and the network
     * {@code initialize()} is deferred inside the cached {@code ready} Mono.
     */
    private PooledClient acquire(String serverId, McpServer server) {
        String fingerprint = fingerprint(server);
        return pool.compute(serverId, (id, existing) -> {
            if (existing != null && existing.fingerprint().equals(fingerprint)) {
                return existing;
            }
            if (existing != null) {
                closeQuietly(existing.client());
            }
            McpAsyncClient client = buildClient(server);
            Mono<McpAsyncClient> ready = client.initialize()
                    .doOnNext(result -> log.info("MCP client initialized for {}: {}", serverId, result))
                    .thenReturn(client)
                    .cache();
            return new PooledClient(fingerprint, client, ready);
        });
    }

    private void evict(String serverId, PooledClient pooled) {
        if (pool.remove(serverId, pooled)) {
            closeQuietly(pooled.client());
        }
    }

    private void closeQuietly(McpAsyncClient client) {
        client.closeGracefully().subscribe(
                ignored -> { },
                error -> log.debug("MCP client close failed", error)
        );
    }

    /** Effective per-request timeout: the server override, else the app default. */
    private Duration requestTimeout(McpServer server) {
        long millis = server.getRequestTimeoutMs() != null
                ? server.getRequestTimeoutMs()
                : defaultRequestTimeoutMs;
        return Duration.ofMillis(millis);
    }

    /** A per-server timeout overrides both phases; otherwise startup gets a larger budget. */
    private Duration initializationTimeout(McpServer server) {
        long millis = server.getRequestTimeoutMs() != null
                ? server.getRequestTimeoutMs()
                : defaultInitializationTimeoutMs;
        return Duration.ofMillis(millis);
    }

    /** Connection-relevant config; a change invalidates the pooled client. */
    private String fingerprint(McpServer server) {
        return String.join("|",
                server.getTransport() == null ? "" : server.getTransport().name(),
                server.getBaseUrl() == null ? "" : server.getBaseUrl(),
                server.getEndpoint() == null ? "" : server.getEndpoint(),
                server.getCommand() == null ? "" : server.getCommand(),
                server.getArgs() == null ? "" : server.getArgs().toString(),
                server.getEnv() == null ? "" : server.getEnv().toString(),
                server.getSecretEnv() == null ? "" : server.getSecretEnv().toString(),
                server.getAuthEnvVar() == null ? "" : server.getAuthEnvVar(),
                server.getAuthType() == null ? "" : server.getAuthType().name(),
                server.getAuthTokenEncrypted() == null ? "" : server.getAuthTokenEncrypted(),
                server.getSecretHeaders() == null ? "" : server.getSecretHeaders().toString(),
                server.getAuthHeaderName() == null ? "" : server.getAuthHeaderName(),
                server.getAuthUsername() == null ? "" : server.getAuthUsername(),
                server.getRequestTimeoutMs() == null ? "" : server.getRequestTimeoutMs().toString());
    }

    @PreDestroy
    void shutdown() {
        pool.values().forEach(pooled -> {
            try {
                pooled.client().closeGracefully().block(Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // Best effort on shutdown.
            }
        });
        pool.clear();
    }

    private McpServer findEnabledServer(String serverId) {
        McpServer server = mcpServerRepository.findByServerId(serverId)
                .orElseThrow(() -> new EntityNotFoundException(MCP_SERVER_NOT_FOUND_MESSAGE));

        if (server.getStatus() != McpServerStatus.ENABLED) {
            // Drop any client held for a server that has since been disabled.
            PooledClient pooled = pool.get(serverId);
            if (pooled != null) {
                evict(serverId, pooled);
            }
            throw new IllegalStateException("MCP server is disabled: " + serverId);
        }
        return server;
    }

    private record PooledClient(
            String fingerprint,
            McpAsyncClient client,
            Mono<McpAsyncClient> ready
    ) {
    }

    private McpAsyncClient buildClient(McpServer server) {
        McpClientTransport transport = switch (server.getTransport()) {
            case HTTP -> buildHttpTransport(server);
            case STDIO -> buildStdioTransport(server);
        };
        return McpClient.async(transport)
                .requestTimeout(requestTimeout(server))
                .initializationTimeout(initializationTimeout(server))
                .toolsChangeConsumer(tools -> Mono.fromRunnable(() ->
                        log.info("MCP tools updated for {}: {}", server.getServerId(), tools)
                ))
                .progressConsumer(progress -> Mono.fromRunnable(() ->
                        log.info("MCP progress for {}: {}", server.getServerId(), progress)
                ))
                .build();
    }

    private McpClientTransport buildHttpTransport(McpServer server) {
        var transportBuilder = HttpClientStreamableHttpTransport
                .builder(server.getBaseUrl())
                .endpoint(server.getEndpoint());

        HttpRequest.Builder authHeader = authHeader(server);
        if (authHeader != null) {
            // The transport copies this seed builder per request, so the auth
            // header rides on every call while it adds its own headers.
            transportBuilder.requestBuilder(authHeader);
        }
        return transportBuilder.build();
    }

    /** The seeded request builder carrying this server's auth header, or null for NONE. */
    HttpRequest.Builder authHeader(McpServer server) {
        return switch (server.getAuthType()) {
            case NONE -> null;
            case BEARER_TOKEN -> HttpRequest.newBuilder()
                    .header("Authorization", "Bearer " + resolveToken(server));
            case API_KEY -> HttpRequest.newBuilder()
                    .header(server.getAuthHeaderName(), resolveToken(server));
            case BASIC -> HttpRequest.newBuilder()
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                            (server.getAuthUsername() + ":" + resolveToken(server))
                                    .getBytes(StandardCharsets.UTF_8)));
            case CUSTOM_HEADERS -> {
                if (server.getSecretHeaders() == null || server.getSecretHeaders().isEmpty()) {
                    throw new IllegalStateException(
                            "No custom authentication headers configured for MCP server: "
                                    + server.getServerId());
                }
                HttpRequest.Builder builder = HttpRequest.newBuilder();
                server.getSecretHeaders().forEach(
                        (name, encrypted) -> builder.header(name, secretCipher.decrypt(encrypted)));
                yield builder;
            }
        };
    }

    private McpClientTransport buildStdioTransport(McpServer server) {
        // Fail fast and clearly if the command isn't present in the backend's
        // environment. Otherwise the SDK spawns on a background thread and only
        // surfaces a generic "failed to initialize" after the request timeout.
        if (!commandResolvable(server.getCommand())) {
            throw new McpConnectionException(
                    "Could not launch MCP server '" + server.getServerId() + "': command '"
                            + server.getCommand() + "' was not found in the backend's environment. "
                            + "If Voyager runs in a container, use HTTP transport or install the runtime "
                            + "(e.g. Node/Python) in the image.",
                    null);
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (server.getEnv() != null) {
            env.putAll(server.getEnv());
        }
        if (server.getSecretEnv() != null) {
            // Decrypt secret env values only here, at spawn time; never persisted plaintext.
            server.getSecretEnv().forEach((name, encrypted) ->
                    env.put(name, secretCipher.decrypt(encrypted)));
        }
        if (server.getAuthType() == McpAuthType.BEARER_TOKEN) {
            // The resolved secret is injected into the child process environment,
            // never persisted — same tiering as the HTTP bearer header.
            env.put(server.getAuthEnvVar(), resolveToken(server));
        }
        ServerParameters params = ServerParameters.builder(server.getCommand())
                .args(server.getArgs() == null ? List.of() : server.getArgs())
                .env(env)
                .build();
        return new StdioClientTransport(params, McpJsonDefaults.getMapper());
    }

    private String resolveToken(McpServer server) {
        String token = secretCipher.decrypt(server.getAuthTokenEncrypted());
        if (token == null) {
            throw new IllegalStateException(
                    "No token configured for MCP server: " + server.getServerId());
        }
        return token;
    }
}
