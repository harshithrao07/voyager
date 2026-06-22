package com.job.scheduler.service;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.entity.McpToolExecution;
import com.job.scheduler.dto.McpToolExecutionResponseDTO;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpToolExecutionStatus;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.repository.McpToolExecutionRepository;
import com.job.scheduler.repository.McpToolRepository;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class McpToolExecutionService {
    private static final String MCP_SERVER_NOT_FOUND_MESSAGE = "MCP server does not exist";
    private static final String MCP_TOOL_NOT_FOUND_MESSAGE = "MCP tool does not exist";

    private final McpServerRepository mcpServerRepository;
    private final McpToolRepository mcpToolRepository;
    private final McpToolExecutionRepository mcpToolExecutionRepository;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;
    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    public Mono<McpSchema.CallToolResult> callTool(
            String serverId,
            String toolName,
            Map<String, Object> arguments,
            McpTrustLevel maxAllowedTrustLevel
    ) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        return Mono.defer(() -> {
            ExecutionContext context = createExecutionContext(
                    serverId,
                    toolName,
                    safeArguments,
                    maxAllowedTrustLevel
            );
            try {
                validateExecution(context, safeArguments, maxAllowedTrustLevel);
            } catch (RuntimeException exception) {
                markRejected(context.execution(), exception);
                return Mono.error(exception);
            }

            return mcpClientService.callTool(serverId, toolName, safeArguments)
                    .doOnNext(result -> markSuccess(context.execution(), result))
                    .doOnError(error -> markFailed(context.execution(), error));
        });
    }

    public List<McpToolExecutionResponseDTO> getExecutions(String serverId, String toolName) {
        List<McpToolExecution> executions = toolName == null || toolName.isBlank()
                ? mcpToolExecutionRepository.findByServerIdOrderByStartedAtDesc(serverId)
                : mcpToolExecutionRepository.findByServerIdAndToolNameOrderByStartedAtDesc(serverId, toolName);

        return executions.stream()
                .map(this::toResponse)
                .toList();
    }

    private void validateExecution(
            ExecutionContext context,
            Map<String, Object> arguments,
            McpTrustLevel maxAllowedTrustLevel
    ) {
        McpServer server = context.server();

        if (server.getStatus() != McpServerStatus.ENABLED) {
            throw new IllegalStateException("MCP server is disabled: " + context.execution().getServerId());
        }
        if (server.getTrustLevel() == McpTrustLevel.UNTRUSTED) {
            throw new IllegalStateException("MCP server is untrusted: " + context.execution().getServerId());
        }

        McpTool tool = context.tool();
        if (tool == null) {
            throw new EntityNotFoundException(MCP_TOOL_NOT_FOUND_MESSAGE);
        }

        if (!tool.isEnabled()) {
            throw new IllegalStateException("MCP tool is disabled: " + context.execution().getToolName());
        }
        validateArguments(tool, arguments);

        McpTrustLevel effectiveMaxTrust = maxAllowedTrustLevel == null
                ? McpTrustLevel.READ_ONLY
                : maxAllowedTrustLevel;

        if (trustRank(server.getTrustLevel()) > trustRank(effectiveMaxTrust)) {
            throw new IllegalStateException(
                    "MCP server trust level " + server.getTrustLevel()
                            + " exceeds allowed level " + effectiveMaxTrust
            );
        }
    }

    private ExecutionContext createExecutionContext(
            String serverId,
            String toolName,
            Map<String, Object> arguments,
            McpTrustLevel maxAllowedTrustLevel
    ) {
        McpServer server = mcpServerRepository.findByServerId(serverId)
                .orElseThrow(() -> new EntityNotFoundException(MCP_SERVER_NOT_FOUND_MESSAGE));
        McpTool tool = mcpToolRepository.findByMcpServerAndToolName(server, toolName).orElse(null);
        McpToolExecution execution = new McpToolExecution();
        execution.setMcpServer(server);
        execution.setMcpTool(tool);
        execution.setServerId(serverId);
        execution.setToolName(toolName);
        execution.setArguments(writeJson(arguments));
        execution.setStatus(McpToolExecutionStatus.RUNNING);
        execution.setMaxAllowedTrustLevel(maxAllowedTrustLevel == null ? McpTrustLevel.READ_ONLY : maxAllowedTrustLevel);
        execution.setStartedAt(Instant.now());
        return new ExecutionContext(server, tool, mcpToolExecutionRepository.save(execution));
    }

    private void markSuccess(McpToolExecution execution, McpSchema.CallToolResult result) {
        execution.setStatus(McpToolExecutionStatus.SUCCESS);
        execution.setResult(writeJson(result));
        execution.setErrorMessage(null);
        complete(execution);
        mcpToolExecutionRepository.save(execution);
    }

    private void markFailed(McpToolExecution execution, Throwable error) {
        execution.setStatus(McpToolExecutionStatus.FAILED);
        execution.setErrorMessage(error.getMessage());
        complete(execution);
        mcpToolExecutionRepository.save(execution);
    }

    private void markRejected(McpToolExecution execution, RuntimeException exception) {
        execution.setStatus(McpToolExecutionStatus.REJECTED);
        execution.setErrorMessage(exception.getMessage());
        complete(execution);
        mcpToolExecutionRepository.save(execution);
    }

    private void complete(McpToolExecution execution) {
        Instant completedAt = Instant.now();
        execution.setCompletedAt(completedAt);
        execution.setDurationMs(Duration.between(execution.getStartedAt(), completedAt).toMillis());
    }

    private void validateArguments(McpTool tool, Map<String, Object> arguments) {
        JsonNode schemaNode = readJson(tool.getInputSchema(), "input schema");
        JsonNode argumentsNode = objectMapper.valueToTree(arguments == null ? Map.of() : arguments);
        Schema schema = schemaRegistry.getSchema(schemaNode);
        List<Error> errors = schema.validate(argumentsNode);

        if (!errors.isEmpty()) {
            String message = errors.stream()
                    .map(Error::getMessage)
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException("MCP tool arguments do not match input schema: " + message);
        }
    }

    private JsonNode readJson(String value, String label) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read MCP tool " + label, exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not serialize MCP tool execution data", exception);
        }
    }

    private McpToolExecutionResponseDTO toResponse(McpToolExecution execution) {
        return new McpToolExecutionResponseDTO(
                execution.getId(),
                execution.getServerId(),
                execution.getToolName(),
                readJson(execution.getArguments(), "execution arguments"),
                execution.getResult() == null ? null : readJson(execution.getResult(), "execution result"),
                execution.getStatus(),
                execution.getMaxAllowedTrustLevel(),
                execution.getErrorMessage(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                execution.getDurationMs(),
                execution.getCreatedAt(),
                execution.getUpdatedAt()
        );
    }

    private int trustRank(McpTrustLevel trustLevel) {
        return switch (trustLevel) {
            case READ_ONLY -> 1;
            case WRITE -> 2;
            case DESTRUCTIVE -> 3;
            case UNTRUSTED -> 99;
        };
    }

    private record ExecutionContext(McpServer server, McpTool tool, McpToolExecution execution) {
    }
}
