package com.job.scheduler.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretReferencesTest {

    @Test
    void parsesBothSupportedReferenceFormats() {
        assertThat(SecretReferences.referenceFromValue("${secret:GITHUB_TOKEN}"))
                .contains("GITHUB_TOKEN");
        assertThat(SecretReferences.referenceFromValue("ref:GITHUB_TOKEN"))
                .contains("GITHUB_TOKEN");
    }

    @Test
    void allowsNonSecretEnvironmentLiterals() {
        SecretReferences.validateEnvironment(Map.of(
                "LOG_LEVEL", "info",
                "WORKSPACE", "/data"
        ));
    }

    @Test
    void rejectsRawSecretLookingEnvironmentValues() {
        assertThatThrownBy(() -> SecretReferences.validateEnvironment(Map.of(
                "GITHUB_TOKEN", "ghp_plaintext"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use ${secret:REF} or ref:REF");
    }

    @Test
    void acceptsReferencedSecretEnvironmentValues() {
        SecretReferences.validateEnvironment(Map.of(
                "GITHUB_TOKEN", "${secret:MCP_GITHUB_TOKEN}",
                "SLACK_API_KEY", "ref:SLACK_API_KEY"
        ));
    }
}
