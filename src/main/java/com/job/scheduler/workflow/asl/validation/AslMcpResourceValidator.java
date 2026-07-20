package com.job.scheduler.workflow.asl.validation;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.repository.McpToolRepository;
import com.job.scheduler.workflow.task.McpTaskResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    private final ObjectMapper objectMapper;

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
                validateResource(
                        state.path("Resource"),
                        state.path("Arguments"),
                        stateLocation,
                        issues
                );
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
            JsonNode argumentsNode,
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
        McpTool tool = mcpToolRepository
                .findByMcpServerAndToolName(server, ref.toolName())
                .orElse(null);
        if (tool == null) {
            issues.add(new AslValidationIssue(
                    location,
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "MCP_TOOL_NOT_FOUND",
                    "MCP tool is not known on server " + ref.serverId() + ": "
                            + ref.toolName() + " (sync the server's tools first)"
            ));
            return;
        }
        validateArguments(tool, argumentsNode, location + ".Arguments", issues);
    }

    private void validateArguments(
            McpTool tool,
            JsonNode argumentsNode,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (tool.getInputSchema() == null || tool.getInputSchema().isBlank()) {
            return;
        }
        try {
            JsonNode schema = objectMapper.readTree(tool.getInputSchema());
            JsonNode properties = schema.path("properties");
            if (!properties.isObject()) {
                return;
            }
            Set<String> allowed = new LinkedHashSet<>();
            properties.properties().forEach(entry -> allowed.add(entry.getKey()));

            Set<String> actual = new LinkedHashSet<>();
            if (argumentsNode.isObject()) {
                argumentsNode.properties().forEach(entry -> actual.add(entry.getKey()));
            }

            Set<String> required = new LinkedHashSet<>();
            JsonNode requiredNode = schema.path("required");
            if (requiredNode.isArray()) {
                requiredNode.forEach(name -> {
                    if (name.isString()) {
                        required.add(name.stringValue());
                    }
                });
            }
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(actual);
            if (!missing.isEmpty()) {
                issues.add(new AslValidationIssue(
                        location,
                        AslValidationCategory.RUNTIME_SUPPORT,
                        "MCP_ARGUMENT_REQUIRED",
                        "MCP tool " + tool.getToolName() + " is missing required Arguments "
                                + missing + "; expected schema keys " + allowed
                ));
            }

            if (schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").booleanValue()) {
                Set<String> unknown = new LinkedHashSet<>(actual);
                unknown.removeAll(allowed);
                if (!unknown.isEmpty()) {
                    issues.add(new AslValidationIssue(
                            location,
                            AslValidationCategory.RUNTIME_SUPPORT,
                            "MCP_ARGUMENT_UNKNOWN",
                            "MCP tool " + tool.getToolName() + " does not allow Arguments "
                                    + unknown + "; use only schema keys " + allowed
                    ));
                }
            }
        } catch (Exception exception) {
            // A malformed stored schema is a registry problem, not an ASL authoring error.
        }
    }

    private String typeOf(JsonNode state) {
        JsonNode type = state.path("Type");
        return type.isString() ? type.stringValue() : null;
    }
}
