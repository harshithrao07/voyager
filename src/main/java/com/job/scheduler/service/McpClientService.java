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
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);
    private static final String MCP_SERVER_NOT_FOUND_MESSAGE = "MCP server does not exist";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final McpServerRepository mcpServerRepository;

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

    private <T> Mono<T> withClient(String serverId, java.util.function.Function<McpAsyncClient, Mono<T>> operation) {
        return Mono.defer(() -> {
            McpServer server = findEnabledServer(serverId);
            return Mono.usingWhen(
                    Mono.just(buildClient(server)),
                    client -> client.initialize()
                            .doOnNext(result -> log.info("MCP client initialized for {}: {}", serverId, result))
                            .then(operation.apply(client)),
                    McpAsyncClient::closeGracefully,
                    (client, error) -> client.closeGracefully(),
                    McpAsyncClient::closeGracefully
            );
        });
    }

    private McpServer findEnabledServer(String serverId) {
        McpServer server = mcpServerRepository.findByServerId(serverId)
                .orElseThrow(() -> new EntityNotFoundException(MCP_SERVER_NOT_FOUND_MESSAGE));

        if (server.getStatus() != McpServerStatus.ENABLED) {
            throw new IllegalStateException("MCP server is disabled: " + serverId);
        }
        return server;
    }

    private McpAsyncClient buildClient(McpServer server) {
        if (server.getTransport() != McpTransport.HTTP) {
            throw new IllegalArgumentException("Unsupported MCP transport: " + server.getTransport());
        }
        if (server.getAuthType() == McpAuthType.BEARER_TOKEN) {
            throw new IllegalStateException("Bearer token MCP clients need a token resolver before use");
        }

        var transport = HttpClientStreamableHttpTransport
                .builder(server.getBaseUrl())
                .endpoint(server.getEndpoint())
                .build();

        return McpClient.async(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .toolsChangeConsumer(tools -> Mono.fromRunnable(() ->
                        log.info("MCP tools updated for {}: {}", server.getServerId(), tools)
                ))
                .progressConsumer(progress -> Mono.fromRunnable(() ->
                        log.info("MCP progress for {}: {}", server.getServerId(), progress)
                ))
                .build();
    }
}
