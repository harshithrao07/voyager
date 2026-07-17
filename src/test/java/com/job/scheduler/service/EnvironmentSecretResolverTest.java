package com.job.scheduler.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentSecretResolverTest {

    private final MockEnvironment environment = new MockEnvironment();
    private final EnvironmentSecretResolver resolver = new EnvironmentSecretResolver(environment);

    @Test
    void resolvesGenericInlineSecret() {
        environment.setProperty("scheduler.secrets.values.GITHUB_TOKEN", "  ghp_inline  ");

        assertThat(resolver.resolve("GITHUB_TOKEN")).contains("ghp_inline");
    }

    @Test
    void resolvesSecretFromMountedFileAndPicksUpRotation() throws IOException {
        Path tokenFile = createSecretFile("github-token", "first-value\n");
        environment.setProperty("scheduler.secrets.files.GITHUB_TOKEN", tokenFile.toString());

        assertThat(resolver.resolve("GITHUB_TOKEN")).contains("first-value");

        Files.writeString(tokenFile, "rotated-value\n");
        assertThat(resolver.resolve("GITHUB_TOKEN")).contains("rotated-value");
    }

    @Test
    void fileSecretTakesPrecedenceOverInlineSecret() throws IOException {
        Path secretFile = createSecretFile("github-token", "file-value");
        environment.setProperty("scheduler.secrets.values.GITHUB_TOKEN", "inline-value");
        environment.setProperty("scheduler.secrets.files.GITHUB_TOKEN", secretFile.toString());

        assertThat(resolver.resolve("GITHUB_TOKEN")).contains("file-value");
    }

    @Test
    void supportsLegacyMcpPropertiesDuringMigration() {
        environment.setProperty("scheduler.mcp.tokens.GITHUB_TOKEN", "legacy-value");

        assertThat(resolver.resolve("GITHUB_TOKEN")).contains("legacy-value");
    }

    @Test
    void returnsEmptyWhenNoSecretConfigured() {
        assertThat(resolver.resolve("GITHUB_TOKEN")).isEmpty();
    }

    @Test
    void rejectsInvalidReference() {
        assertThatThrownBy(() -> resolver.resolve("secret/github"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Secret reference must use UPPER_SNAKE_CASE");
    }

    @Test
    void rejectsUnreadableSecretFile() {
        environment.setProperty("scheduler.secrets.files.GITHUB_TOKEN", "/does/not/exist/token");

        assertThatThrownBy(() -> resolver.resolve("GITHUB_TOKEN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not read secret file");
    }

    @Test
    void rejectsEmptySecretFile() throws IOException {
        Path secretFile = createSecretFile("empty-token", "   \n");
        environment.setProperty("scheduler.secrets.files.GITHUB_TOKEN", secretFile.toString());

        assertThatThrownBy(() -> resolver.resolve("GITHUB_TOKEN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is empty");
    }

    private Path createSecretFile(String name, String value) throws IOException {
        Path directory = Files.createDirectories(
                Path.of("target", "test-secret-files", UUID.randomUUID().toString())
        );
        Path file = directory.resolve(name);
        Files.writeString(file, value);
        return file;
    }
}
