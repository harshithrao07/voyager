package com.job.scheduler.service;

import com.job.scheduler.dto.ElevatedMcpToolDTO;
import com.job.scheduler.dto.WorkflowAiTrustReviewDTO;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.workflow.task.McpTaskResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans a workflow definition for MCP Task resources that grant elevated
 * ({@code WRITE} or {@code DESTRUCTIVE}) trust via {@code ?trust=...}. These are the
 * mutating tools the assistant wired in; the save flow requires the user to confirm
 * them before the workflow is created or activated.
 *
 * <p>Mirrors the state walk in {@code AslMcpResourceValidator} so nested MCP calls
 * inside Parallel branches and Map item processors are covered too.
 */
@Service
@RequiredArgsConstructor
public class WorkflowAiTrustReviewService {

    private final McpServerRepository mcpServerRepository;

    public WorkflowAiTrustReviewDTO review(JsonNode definition) {
        if (definition == null || !definition.isObject()) {
            return WorkflowAiTrustReviewDTO.none();
        }
        List<ElevatedMcpToolDTO> tools = new ArrayList<>();
        collectStates(definition.path("States"), tools);
        return new WorkflowAiTrustReviewDTO(!tools.isEmpty(), List.copyOf(tools));
    }

    private void collectStates(JsonNode states, List<ElevatedMcpToolDTO> tools) {
        if (!states.isObject()) {
            return;
        }
        states.properties().forEach(entry -> {
            JsonNode state = entry.getValue();
            if ("Task".equals(textOf(state.path("Type")))) {
                collectResource(entry.getKey(), state.path("Resource"), tools);
            }
            JsonNode branches = state.path("Branches");
            if (branches.isArray()) {
                branches.forEach(branch -> collectStates(branch.path("States"), tools));
            }
            JsonNode itemProcessor = state.path("ItemProcessor");
            if (itemProcessor.isObject()) {
                collectStates(itemProcessor.path("States"), tools);
            }
        });
    }

    private void collectResource(String stateName, JsonNode resourceNode, List<ElevatedMcpToolDTO> tools) {
        if (!resourceNode.isString()) {
            return;
        }
        String resource = resourceNode.stringValue();
        McpTaskResource.McpResourceRef ref;
        try {
            ref = McpTaskResource.parseMcpResource(resource);
        } catch (IllegalArgumentException exception) {
            // Malformed MCP resources are surfaced by save-time ASL validation, not here.
            return;
        }
        if (ref == null) {
            return;
        }
        if (ref.trustLevel() != McpTrustLevel.WRITE && ref.trustLevel() != McpTrustLevel.DESTRUCTIVE) {
            return;
        }
        McpServer server = mcpServerRepository.findByServerId(ref.serverId()).orElse(null);
        tools.add(new ElevatedMcpToolDTO(
                stateName,
                ref.serverId(),
                ref.toolName(),
                ref.trustLevel(),
                server == null ? null : server.getDisplayName(),
                server == null ? null : server.getTrustLevel()
        ));
    }

    private String textOf(JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }
}
