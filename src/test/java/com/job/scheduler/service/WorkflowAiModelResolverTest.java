package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.AiStructuredOutputMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowAiModelResolverTest {
    private static final String KEY = "xYZP4KD/T0APHH/9GMLiO9vt8D/GFCJXwLzh5ALiGV0=";

    private final SecretCipher cipher = new SecretCipher(KEY);
    private final WorkflowAiModelResolver modelResolver = new WorkflowAiModelResolver(cipher);

    @Test
    void decryptsStoredCredentialAtWorkflowRuntime() {
        AiModelConfig config = model(cipher.encrypt("runtime-only-value"));

        assertThat(modelResolver.resolve(config)).isNotNull();
    }

    @Test
    void localModelWithoutCredentialNeedsNoSecret() {
        assertThat(modelResolver.resolve(model(null))).isNotNull();
    }

    @Test
    void startsWithStrictSchemaAndCachesTheAcceptedModeOnTheRegisteredModel() {
        AiModelConfig config = model(null);

        assertThat(modelResolver.preferredStructuredOutputMode(config))
                .isEqualTo(AiStructuredOutputMode.STRICT_JSON_SCHEMA);

        modelResolver.recordStructuredOutputMode(config, AiStructuredOutputMode.JSON_OBJECT);

        assertThat(config.getStructuredOutputMode())
                .isEqualTo(AiStructuredOutputMode.JSON_OBJECT);
        assertThat(modelResolver.preferredStructuredOutputMode(config))
                .isEqualTo(AiStructuredOutputMode.JSON_OBJECT);
    }

    @Test
    void cloudflareStartsWithItsNonStrictSchemaDialect() {
        AiModelConfig config = model(null);
        config.setBaseUrl("https://api.cloudflare.com/client/v4/accounts/account/ai/v1");

        assertThat(modelResolver.preferredStructuredOutputMode(config))
                .isEqualTo(AiStructuredOutputMode.JSON_SCHEMA);
    }

    private AiModelConfig model(String credentialEncrypted) {
        AiModelConfig config = new AiModelConfig();
        config.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_LOCAL);
        config.setBaseUrl("http://localhost:11434/v1");
        config.setModelName("local-model");
        config.setCredentialEncrypted(credentialEncrypted);
        return config;
    }
}
