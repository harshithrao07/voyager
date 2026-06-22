package com.job.scheduler.workflow.task;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.dto.payload.McpToolPayload;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.handlers.McpToolHandler;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Locale;

/**
 * Task resource for {@code mcp://serverId/toolName}. Classifies trust-level
 * rejections as {@link TaskResourceErrors#PERMISSIONS} and missing servers/tools
 * as {@link TaskResourceErrors#MCP_TOOL_NOT_FOUND}.
 */
@Component
@RequiredArgsConstructor
public class McpTaskResource implements TaskResource {
    private final McpToolHandler mcpToolHandler;

    @Override
    public boolean supports(URI resource) {
        return "mcp".equals(resource.getScheme());
    }

    @Override
    public JsonNode execute(URI resource, JsonNode arguments) {
        String serverId = resource.getHost();
        String toolName = trimSlashes(resource.getPath());
        if (serverId == null || serverId.isBlank()
                || toolName == null || toolName.isBlank()) {
            throw new TaskResourceException(
                    TaskResourceErrors.TASK_FAILED,
                    "MCP Task resource must be mcp://serverId/toolName"
            );
        }
        try {
            StepResult result = mcpToolHandler.handle(new McpToolPayload(
                    serverId,
                    toolName,
                    arguments,
                    McpTrustLevel.READ_ONLY
            ));
            return TaskResourceOutput.of(result);
        } catch (TaskResourceException exception) {
            throw exception;
        } catch (EntityNotFoundException exception) {
            throw new TaskResourceException(
                    TaskResourceErrors.MCP_TOOL_NOT_FOUND,
                    exception.getMessage(),
                    exception
            );
        } catch (IllegalArgumentException exception) {
            // Argument shape/schema mismatch — an authoring error.
            throw new TaskResourceException(
                    TaskResourceErrors.TASK_FAILED,
                    exception.getMessage(),
                    exception
            );
        } catch (RuntimeException exception) {
            throw classify(exception);
        }
    }

    private TaskResourceException classify(RuntimeException exception) {
        String message = exception.getMessage() == null
                ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        // Trust-level rejections (server untrusted, or trust exceeds allowed)
        // are authorization failures. Pending typed MCP exceptions, classified
        // by message keyword.
        if (message.contains("trust") || message.contains("untrusted")) {
            return new TaskResourceException(
                    TaskResourceErrors.PERMISSIONS,
                    exception.getMessage(),
                    exception
            );
        }
        return new TaskResourceException(
                TaskResourceErrors.MCP_TOOL_FAILED,
                exception.getMessage(),
                exception
        );
    }
}
