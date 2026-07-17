package com.job.scheduler.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves secrets from environment-backed properties or mounted secret files.
 * File-backed secrets win and are read on every resolve, enabling rotation.
 */
@Component
@RequiredArgsConstructor
public class EnvironmentSecretResolver implements SecretResolver {
    private static final String VALUE_PREFIX = "scheduler.secrets.values.";
    private static final String FILE_PREFIX = "scheduler.secrets.files.";

    // Backward-compatible aliases for existing MCP deployments.
    private static final String LEGACY_MCP_VALUE_PREFIX = "scheduler.mcp.tokens.";
    private static final String LEGACY_MCP_FILE_PREFIX = "scheduler.mcp.token-files.";

    private final Environment environment;

    @Override
    public Optional<String> resolve(String secretRef) {
        String normalizedRef = SecretReferences.requireValidReference(secretRef);

        String filePath = firstConfigured(
                FILE_PREFIX + normalizedRef,
                LEGACY_MCP_FILE_PREFIX + normalizedRef
        );
        if (filePath != null) {
            return Optional.of(readSecretFile(filePath, normalizedRef));
        }

        String inlineValue = firstConfigured(
                VALUE_PREFIX + normalizedRef,
                LEGACY_MCP_VALUE_PREFIX + normalizedRef
        );
        return inlineValue == null
                ? Optional.empty()
                : Optional.of(inlineValue.trim());
    }

    private String firstConfigured(String... propertyNames) {
        for (String propertyName : propertyNames) {
            String value = environment.getProperty(propertyName);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String readSecretFile(String filePath, String ref) {
        try {
            String contents = Files.readString(Path.of(filePath)).trim();
            if (contents.isEmpty()) {
                throw new IllegalStateException(
                        "Secret file for reference " + ref + " is empty: " + filePath
                );
            }
            return contents;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not read secret file for reference " + ref + ": " + filePath,
                    exception
            );
        }
    }
}
