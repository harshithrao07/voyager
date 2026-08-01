package com.job.scheduler.service;

import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.ResourceEmbeddingType;
import com.job.scheduler.repository.FunctionDefinitionRepository;
import com.job.scheduler.repository.McpToolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Single source of truth for the enabled catalog resources (functions + MCP tools) and the exact
 * text used to represent each one. Both the embedding reconcile ({@link WorkflowAiEmbeddingService})
 * and the retrieval ranking ({@link EmbeddingRankingService}) go through here, so the ranking eval
 * embeds byte-for-byte the same text production does.
 */
@Component
@RequiredArgsConstructor
public class CatalogResourceProvider {
    private final FunctionDefinitionRepository functionRepository;
    private final McpToolRepository mcpToolRepository;

    /** A catalog entry to embed: its type/id, a display name, and the text fed to the embedder. */
    public record CatalogResource(
            ResourceEmbeddingType type,
            UUID id,
            String name,
            String text
    ) {
    }

    public List<CatalogResource> enabledResources() {
        List<CatalogResource> resources = new ArrayList<>();
        for (FunctionDefinition function : functionRepository
                .findByStatusNotOrderByUpdatedAtDesc(FunctionStatus.ARCHIVED)) {
            if (function.getStatus() != FunctionStatus.ENABLED
                    || function.getActiveVersion() == null) {
                continue;
            }
            resources.add(new CatalogResource(
                    ResourceEmbeddingType.FUNCTION,
                    function.getId(),
                    function.getName(),
                    functionText(function)
            ));
        }
        for (McpTool tool : mcpToolRepository.findByEnabledTrue()) {
            McpServer server = tool.getMcpServer();
            if (server == null || server.getStatus() != McpServerStatus.ENABLED) {
                continue;
            }
            resources.add(new CatalogResource(
                    ResourceEmbeddingType.MCP_TOOL,
                    tool.getId(),
                    tool.getToolName(),
                    mcpToolText(tool)
            ));
        }
        return resources;
    }

    public static String functionText(FunctionDefinition function) {
        String description = function.getDescription() == null ? "" : function.getDescription();
        return (function.getName() + " " + description).trim();
    }

    public static String mcpToolText(McpTool tool) {
        McpServer server = tool.getMcpServer();
        StringBuilder text = new StringBuilder();
        if (server != null) {
            text.append(safe(server.getServerId())).append(' ');
        }
        text.append(safe(tool.getToolName())).append(' ')
                .append(safe(tool.getTitle())).append(' ')
                .append(safe(tool.getDescription()));
        return text.toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
