package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowAiModelResolverTest {
    @Mock
    private SecretResolver secretResolver;

    private WorkflowAiModelResolver modelResolver;

    @BeforeEach
    void setUp() {
        modelResolver = new WorkflowAiModelResolver(secretResolver);
    }

    @Test
    void resolvesConfiguredCredentialReferenceAtWorkflowRuntime() {
        AiModelConfig config = model("OPENAI_API_KEY");
        when(secretResolver.require("OPENAI_API_KEY")).thenReturn("runtime-only-value");

        assertThat(modelResolver.resolve(config)).isNotNull();

        verify(secretResolver).require("OPENAI_API_KEY");
    }

    @Test
    void localModelWithoutCredentialReferenceNeedsNoSecret() {
        assertThat(modelResolver.resolve(model(null))).isNotNull();

        verifyNoInteractions(secretResolver);
    }

    private AiModelConfig model(String credentialRef) {
        AiModelConfig config = new AiModelConfig();
        config.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_LOCAL);
        config.setBaseUrl("http://localhost:11434/v1");
        config.setModelName("local-model");
        config.setCredentialRef(credentialRef);
        return config;
    }
}
