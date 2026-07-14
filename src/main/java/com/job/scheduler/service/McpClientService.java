package com.job.scheduler.service;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);
    private static final String MCP_SERVER_NOT_FOUND_MESSAGE = "MCP server does not exist";

    private final McpServerRepository mcpServerRepository;
    private final McpTokenResolver tokenResolver;

    /** Default per-request timeout when a server does not override it. */
    @Value("${scheduler.mcp.request-timeout-ms:10000}")
    private long defaultRequestTimeoutMs = 10000;

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
        return Mono.defer(() -> {
            McpServer server = findEnabledServer(serverId);
            PooledClient pooled = acquire(serverId, server);
            return pooled.ready()
                    .flatMap(operation)
                    .doOnError(error -> evict(serverId, pooled));
        });
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

    /** Connection-relevant config; a change invalidates the pooled client. */
    private String fingerprint(McpServer server) {
        return String.join("|",
                server.getTransport() == null ? "" : server.getTransport().name(),
                server.getBaseUrl() == null ? "" : server.getBaseUrl(),
                server.getEndpoint() == null ? "" : server.getEndpoint(),
                server.getAuthType() == null ? "" : server.getAuthType().name(),
                server.getAuthTokenRef() == null ? "" : server.getAuthTokenRef(),
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
        if (server.getTransport() != McpTransport.HTTP) {
            throw new IllegalArgumentException("Unsupported MCP transport: " + server.getTransport());
        }

        var transportBuilder = HttpClientStreamableHttpTransport
                .builder(server.getBaseUrl())
                .endpoint(server.getEndpoint());

        if (server.getAuthType() == McpAuthType.BEARER_TOKEN) {
            String token = tokenResolver.resolve(server)
                    .orElseThrow(() -> new IllegalStateException(
                            "No token configured for MCP server: " + server.getServerId()));
            // The transport copies this seed builder per request, so the
            // Authorization header rides on every call while it adds its own headers.
            transportBuilder.requestBuilder(
                    HttpRequest.newBuilder().header("Authorization", "Bearer " + token));
        }

        return McpClient.async(transportBuilder.build())
                .requestTimeout(requestTimeout(server))
                .toolsChangeConsumer(tools -> Mono.fromRunnable(() ->
                        log.info("MCP tools updated for {}: {}", server.getServerId(), tools)
                ))
                .progressConsumer(progress -> Mono.fromRunnable(() ->
                        log.info("MCP progress for {}: {}", server.getServerId(), progress)
                ))
                .build();
    }
}
