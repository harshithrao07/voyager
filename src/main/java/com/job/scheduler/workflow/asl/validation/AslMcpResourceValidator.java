package com.job.scheduler.workflow.asl.validation;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.repository.McpToolRepository;
import com.job.scheduler.workflow.task.McpTaskResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Save-time checks for {@code voyager://mcp/...} Task resources: the trust level
 * must be valid and grantable, and the referenced server and tool must exist in
 * the registry. Recurses into Parallel branches and Map item processors so
 * nested MCP tasks are covered. Non-MCP resources are ignored.
 */
@Component
@RequiredArgsConstructor
public class AslMcpResourceValidator {
    private final McpServerRepository mcpServerRepository;
    private final McpToolRepository mcpToolRepository;

    public List<AslValidationIssue> validate(JsonNode definition) {
        List<AslValidationIssue> issues = new ArrayList<>();
        validateStates(definition.path("States"), "$.States", issues);
        return List.copyOf(issues);
    }

    private void validateStates(
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (!states.isObject()) {
            return;
        }
        states.properties().forEach(entry -> {
            JsonNode state = entry.getValue();
            String stateLocation = location + "." + entry.getKey();
            if ("Task".equals(typeOf(state))) {
                validateResource(state.path("Resource"), stateLocation, issues);
            }
            JsonNode branches = state.path("Branches");
            if (branches.isArray()) {
                for (int index = 0; index < branches.size(); index++) {
                    validateStates(
                            branches.get(index).path("States"),
                            stateLocation + ".Branches[" + index + "].States",
                            issues
                    );
                }
            }
            JsonNode itemProcessor = state.path("ItemProcessor");
            if (itemProcessor.isObject()) {
                validateStates(
                        itemProcessor.path("States"),
                        stateLocation + ".ItemProcessor.States",
                        issues
                );
            }
        });
    }

    private void validateResource(
            JsonNode resourceNode,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (!resourceNode.isString()) {
            return;
        }
        McpTaskResource.McpResourceRef ref;
        try {
            ref = McpTaskResource.parseMcpResource(resourceNode.stringValue());
        } catch (IllegalArgumentException exception) {
            issues.add(new AslValidationIssue(
                    location,
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "MCP_RESOURCE_INVALID",
                    exception.getMessage()
            ));
            return;
        }
        if (ref == null) {
            return;
        }

        McpServer server = mcpServerRepository.findByServerId(ref.serverId()).orElse(null);
        if (server == null) {
            issues.add(new AslValidationIssue(
                    location,
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "MCP_SERVER_NOT_FOUND",
                    "MCP server is not registered: " + ref.serverId()
            ));
            return;
        }
        boolean toolKnown = mcpToolRepository
                .findByMcpServerAndToolName(server, ref.toolName())
                .isPresent();
        if (!toolKnown) {
            issues.add(new AslValidationIssue(
                    location,
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "MCP_TOOL_NOT_FOUND",
                    "MCP tool is not known on server " + ref.serverId() + ": "
                            + ref.toolName() + " (sync the server's tools first)"
            ));
        }
    }

    private String typeOf(JsonNode state) {
        JsonNode type = state.path("Type");
        return type.isString() ? type.stringValue() : null;
    }
}
