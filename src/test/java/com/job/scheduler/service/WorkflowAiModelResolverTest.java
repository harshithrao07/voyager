package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
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

    private AiModelConfig model(String credentialEncrypted) {
        AiModelConfig config = new AiModelConfig();
        config.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_LOCAL);
        config.setBaseUrl("http://localhost:11434/v1");
        config.setModelName("local-model");
        config.setCredentialEncrypted(credentialEncrypted);
        return config;
    }
}
