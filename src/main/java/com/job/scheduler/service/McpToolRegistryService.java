package com.job.scheduler.service;

import com.job.scheduler.dto.McpToolResponseDTO;
import com.job.scheduler.dto.McpToolSyncResultDTO;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.repository.McpToolRepository;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class McpToolRegistryService {
    private static final String MCP_SERVER_NOT_FOUND_MESSAGE = "MCP server does not exist";

    private final McpServerRepository mcpServerRepository;
    private final McpToolRepository mcpToolRepository;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;
    private final CatalogDescriptionGenerationService descriptionGenerationService;

    /** Default upper bound for a blocking tool sync when a server has no override. */
    @Value("${scheduler.mcp.sync-timeout-ms:60000}")
    private long syncTimeoutMs = 60000;

    public List<McpToolResponseDTO> getKnownTools(String serverId, boolean enabledOnly) {
        McpServer server = findServer(serverId);
        List<McpTool> tools = enabledOnly
                ? mcpToolRepository.findByMcpServerAndEnabledTrueOrderByToolNameAsc(server)
                : mcpToolRepository.findByMcpServerOrderByToolNameAsc(server);

        return tools.stream()
                .map(this::toResponse)
                .toList();
    }

    public McpToolSyncResultDTO syncTools(String serverId) {
        McpServer server = findServer(serverId);
        // Never cut the sync short of the server's own per-request timeout.
        long blockMs = server.getRequestTimeoutMs() != null
                ? Math.max(syncTimeoutMs, server.getRequestTimeoutMs())
                : syncTimeoutMs;
        McpSchema.ListToolsResult listToolsResult =
                mcpClientService.listTools(serverId).block(Duration.ofMillis(blockMs));
        List<McpSchema.Tool> discoveredTools = listToolsResult == null ? List.of() : listToolsResult.tools();
        Instant syncedAt = Instant.now();

        Map<String, McpTool> existingByName = mcpToolRepository.findByMcpServerOrderByToolNameAsc(server)
                .stream()
                .collect(Collectors.toMap(McpTool::getToolName, Function.identity()));

        int createdCount = 0;
        int updatedCount = 0;
        Set<String> discoveredNames = new HashSet<>();

        for (McpSchema.Tool discoveredTool : discoveredTools) {
            discoveredNames.add(discoveredTool.name());
            McpTool persistedTool = existingByName.get(discoveredTool.name());
            if (persistedTool == null) {
                persistedTool = new McpTool();
                persistedTool.setMcpServer(server);
                persistedTool.setToolName(discoveredTool.name());
                createdCount++;
            } else {
                updatedCount++;
            }

            persistedTool.setTitle(discoveredTool.title());
            persistedTool.setInputSchema(writeJson(defaultSchema(discoveredTool.inputSchema())));
            persistedTool.setOutputSchema(discoveredTool.outputSchema() == null ? null : writeJson(discoveredTool.outputSchema()));
            String providerDescription = discoveredTool.description();
            if (providerDescription != null && !providerDescription.isBlank()) {
                persistedTool.setDescription(providerDescription.trim());
            } else if (persistedTool.getDescription() == null || persistedTool.getDescription().isBlank()) {
                persistedTool.setDescription(descriptionGenerationService.describeMcpTool(persistedTool));
            }
            persistedTool.setEnabled(true);
            persistedTool.setLastSeenAt(syncedAt);
            mcpToolRepository.save(persistedTool);
        }

        int disabledCount = 0;
        for (McpTool existingTool : existingByName.values()) {
            if (existingTool.isEnabled() && !discoveredNames.contains(existingTool.getToolName())) {
                existingTool.setEnabled(false);
                mcpToolRepository.save(existingTool);
                disabledCount++;
            }
        }

        List<McpToolResponseDTO> tools = mcpToolRepository.findByMcpServerOrderByToolNameAsc(server)
                .stream()
                .map(this::toResponse)
                .toList();

        return new McpToolSyncResultDTO(
                serverId,
                discoveredTools.size(),
                createdCount,
                updatedCount,
                disabledCount,
                syncedAt,
                tools
        );
    }

    private McpServer findServer(String serverId) {
        return mcpServerRepository.findByServerId(serverId)
                .orElseThrow(() -> new EntityNotFoundException(MCP_SERVER_NOT_FOUND_MESSAGE));
    }

    private Map<String, Object> defaultSchema(Map<String, Object> schema) {
        return schema == null ? Map.of("type", "object") : schema;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not serialize MCP tool schema", exception);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not deserialize MCP tool schema", exception);
        }
    }

    private McpToolResponseDTO toResponse(McpTool tool) {
        return new McpToolResponseDTO(
                tool.getId(),
                tool.getMcpServer().getServerId(),
                tool.getToolName(),
                tool.getTitle(),
                tool.getDescription(),
                readJson(tool.getInputSchema()),
                readJson(tool.getOutputSchema()),
                tool.isEnabled(),
                tool.getLastSeenAt(),
                tool.getCreatedAt(),
                tool.getUpdatedAt()
        );
    }
}
