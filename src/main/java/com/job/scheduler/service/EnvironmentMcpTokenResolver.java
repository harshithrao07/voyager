package com.job.scheduler.service;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpAuthType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Default {@link McpTokenResolver} that sources tokens from the deployment tier
 * (environment variables, {@code .env}, mounted secret files) via Spring's
 * {@link Environment}. Nothing here is stored in the database.
 *
 * <p>For a server whose {@code authTokenRef} is {@code GITHUB_MCP_TOKEN}, provide
 * either:
 * <ul>
 *   <li>an inline value at {@code scheduler.mcp.tokens.GITHUB_MCP_TOKEN}
 *       (env {@code SCHEDULER_MCP_TOKENS_GITHUB_MCP_TOKEN}), or</li>
 *   <li>a file path at {@code scheduler.mcp.token-files.GITHUB_MCP_TOKEN}
 *       (env {@code SCHEDULER_MCP_TOKEN_FILES_GITHUB_MCP_TOKEN}) pointing at a
 *       mounted secret. The file is read fresh on every resolve, so a rotated
 *       Kubernetes/Docker secret is picked up without a restart.</li>
 * </ul>
 * The file variant takes precedence when both are present. Use an
 * {@code UPPER_SNAKE_CASE} ref so it maps cleanly onto an environment variable.
 */
@Component
@RequiredArgsConstructor
public class EnvironmentMcpTokenResolver implements McpTokenResolver {
    private static final String TOKEN_PREFIX = "scheduler.mcp.tokens.";
    private static final String TOKEN_FILE_PREFIX = "scheduler.mcp.token-files.";

    private final Environment environment;

    @Override
    public Optional<String> resolve(McpServer server) {
        if (server.getAuthType() == McpAuthType.NONE) {
            return Optional.empty();
        }

        String ref = server.getAuthTokenRef();
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException(
                    "MCP server " + server.getServerId()
                            + " uses " + server.getAuthType() + " auth but has no authTokenRef");
        }
        String normalizedRef = ref.trim();

        String filePath = environment.getProperty(TOKEN_FILE_PREFIX + normalizedRef);
        if (filePath != null && !filePath.isBlank()) {
            return Optional.of(readTokenFile(filePath.trim(), normalizedRef));
        }

        String inlineToken = environment.getProperty(TOKEN_PREFIX + normalizedRef);
        if (inlineToken != null && !inlineToken.isBlank()) {
            return Optional.of(inlineToken.trim());
        }

        return Optional.empty();
    }

    private String readTokenFile(String filePath, String ref) {
        try {
            // Only the ref/path appear in any message — never the token contents.
            String contents = Files.readString(Path.of(filePath)).trim();
            if (contents.isEmpty()) {
                throw new IllegalStateException(
                        "MCP token file for ref " + ref + " is empty: " + filePath);
            }
            return contents;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not read MCP token file for ref " + ref + ": " + filePath,
                    exception);
        }
    }
}
