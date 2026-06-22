package com.job.scheduler.handlers;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.dto.payload.McpToolPayload;
import com.job.scheduler.service.McpToolExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class McpToolHandler implements TaskHandler<McpToolPayload> {
    private final McpToolExecutionService mcpToolExecutionService;
    private final ObjectMapper objectMapper;

    @Override
    public StepResult handle(McpToolPayload payload) {
        var result = mcpToolExecutionService.callTool(
                        payload.serverId(),
                        payload.toolName(),
                        arguments(payload),
                        payload.maxAllowedTrustLevel()
                )
                .block();
        return result == null
                ? StepResult.empty()
                : new StepResult(objectMapper.valueToTree(result));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> arguments(McpToolPayload payload) {
        if (payload.arguments() == null || payload.arguments().isNull()) {
            return Map.of();
        }

        try {
            return objectMapper.treeToValue(payload.arguments(), Map.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP tool arguments must be a JSON object", exception);
        }
    }
}
