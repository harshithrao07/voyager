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
import java.util.Set;

/**
 * Task resource for {@code voyager://mcp/serverId/toolName}. Classifies
 * trust-level rejections as {@link TaskResourceErrors#PERMISSIONS} and missing
 * servers/tools as {@link TaskResourceErrors#MCP_TOOL_NOT_FOUND}.
 *
 * <p>The maximum trust level a Task grants the call is declared with an optional
 * {@code trust} query parameter, e.g.
 * {@code voyager://mcp/crm/create-lead?trust=WRITE}. It defaults to
 * {@link McpTrustLevel#READ_ONLY} when absent, so a workflow must opt in
 * explicitly to reach a {@code WRITE} or {@code DESTRUCTIVE} server.
 * {@link McpTrustLevel#UNTRUSTED} cannot be granted here.
 */
@Component
@RequiredArgsConstructor
public class McpTaskResource implements TaskResource {
    /** The {@code mcp} category under the {@code voyager} scheme. */
    private static final String CATEGORY = "mcp";
    private static final String TRUST_PARAM = "trust";
    /** Trust levels a Task may grant; {@code UNTRUSTED} is never grantable. */
    private static final Set<McpTrustLevel> GRANTABLE_TRUST_LEVELS = Set.of(
            McpTrustLevel.READ_ONLY, McpTrustLevel.WRITE, McpTrustLevel.DESTRUCTIVE);

    private final McpToolHandler mcpToolHandler;

    @Override
    public boolean supports(URI resource) {
        return "voyager".equals(resource.getScheme())
                && CATEGORY.equals(resource.getHost());
    }

    @Override
    public JsonNode execute(URI resource, JsonNode arguments) {
        McpResourceRef ref;
        try {
            ref = parseMcpResource(resource);
        } catch (IllegalArgumentException exception) {
            throw new TaskResourceException(
                    TaskResourceErrors.TASK_FAILED, exception.getMessage(), exception);
        }
        try {
            StepResult result = mcpToolHandler.handle(new McpToolPayload(
                    ref.serverId(),
                    ref.toolName(),
                    arguments,
                    ref.trustLevel()
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

    /**
     * Parses a {@code voyager://mcp/serverId/toolName[?trust=LEVEL]} resource.
     * Returns {@code null} for any non-MCP resource. Throws
     * {@link IllegalArgumentException} with a human-readable message when the
     * resource is an MCP resource but malformed, or declares an unknown or
     * ungrantable trust level. Shared by execution and save-time validation so
     * the two can never disagree.
     */
    public static McpResourceRef parseMcpResource(String resource) {
        if (resource == null || resource.isBlank()) {
            return null;
        }
        try {
            return parseMcpResource(URI.create(resource));
        } catch (IllegalArgumentException exception) {
            // A syntactically invalid URI only matters when it looks like ours.
            if (resource.startsWith("voyager://" + CATEGORY)) {
                throw new IllegalArgumentException(
                        "MCP Task resource must be voyager://mcp/serverId/toolName");
            }
            return null;
        }
    }

    public static McpResourceRef parseMcpResource(URI uri) {
        if (uri == null
                || !"voyager".equals(uri.getScheme())
                || !CATEGORY.equals(uri.getHost())) {
            return null;
        }
        String path = trimPathSlashes(uri.getPath());
        String serverId = null;
        String toolName = null;
        if (path != null && !path.isBlank()) {
            int separator = path.indexOf('/');
            if (separator > 0 && separator < path.length() - 1) {
                serverId = path.substring(0, separator);
                toolName = path.substring(separator + 1);
            }
        }
        if (serverId == null || serverId.isBlank()
                || toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException(
                    "MCP Task resource must be voyager://mcp/serverId/toolName");
        }
        return new McpResourceRef(serverId, toolName, parseTrustLevel(uri.getQuery()));
    }

    /**
     * Reads the {@code trust} query parameter as the ceiling the Task grants,
     * defaulting to {@link McpTrustLevel#READ_ONLY}. Rejects unknown values and
     * {@link McpTrustLevel#UNTRUSTED} as authoring errors.
     */
    private static McpTrustLevel parseTrustLevel(String query) {
        String raw = queryParam(query, TRUST_PARAM);
        if (raw == null || raw.isBlank()) {
            return McpTrustLevel.READ_ONLY;
        }
        McpTrustLevel level;
        try {
            level = McpTrustLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unknown MCP trust level '" + raw
                            + "'; allowed: READ_ONLY, WRITE, DESTRUCTIVE");
        }
        if (!GRANTABLE_TRUST_LEVELS.contains(level)) {
            throw new IllegalArgumentException(
                    "MCP trust level UNTRUSTED cannot be granted by a Task; "
                            + "allowed: READ_ONLY, WRITE, DESTRUCTIVE");
        }
        return level;
    }

    private static String trimPathSlashes(String value) {
        return value == null ? null : value.replaceAll("^/+|/+$", "");
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals >= 0 ? pair.substring(0, equals) : pair;
            if (name.equals(key)) {
                return equals >= 0 ? pair.substring(equals + 1) : "";
            }
        }
        return null;
    }

    /**
     * Whether a Task resource is a <em>mutating</em> MCP call — an
     * {@code voyager://mcp/...} resource that grants {@code WRITE} or
     * {@code DESTRUCTIVE} trust. Such calls are unsafe to auto-retry: a failure
     * may follow a side effect that already happened on the server, so a retry
     * could duplicate it. READ-only MCP calls and every non-MCP resource are
     * retryable, so this returns {@code false} for them (and for anything
     * unparseable, which will fail at execution before any side effect).
     */
    public static boolean isMutatingResource(String resource) {
        McpResourceRef ref;
        try {
            ref = parseMcpResource(resource);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return ref != null
                && (ref.trustLevel() == McpTrustLevel.WRITE
                || ref.trustLevel() == McpTrustLevel.DESTRUCTIVE);
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

    /** Parsed components of a {@code voyager://mcp/...} Task resource. */
    public record McpResourceRef(
            String serverId,
            String toolName,
            McpTrustLevel trustLevel
    ) {
    }
}
