package com.job.scheduler.service;

import com.job.scheduler.dto.AiModelConfigDTO;
import com.job.scheduler.dto.AiModelConfigRequestDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.AiModelRole;
import com.job.scheduler.repository.AiModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelConfigServiceTest {
    private static final String KEY = "xYZP4KD/T0APHH/9GMLiO9vt8D/GFCJXwLzh5ALiGV0=";

    @Mock
    private AiModelConfigRepository repository;

    private final SecretCipher cipher = new SecretCipher(KEY);
    private AiModelConfigService service;

    @BeforeEach
    void setUp() {
        service = new AiModelConfigService(repository, new ObjectMapper(), cipher);
        lenient().when(repository.save(any(AiModelConfig.class))).thenAnswer(invocation -> {
            AiModelConfig model = invocation.getArgument(0);
            if (model.getId() == null) {
                model.setId(UUID.randomUUID());
            }
            return model;
        });
    }

    @Test
    void encryptsCredentialAndNeverLeaksItInTheResponse() throws Exception {
        when(repository.findByBaseUrlAndModelName(
                "https://api.example.com/v1",
                "cloud-model"
        )).thenReturn(Optional.empty());

        AiModelConfigDTO result = service.createModel(new AiModelConfigRequestDTO(
                "Cloud model",
                AiModelProviderType.OPENAI_COMPATIBLE_API,
                AiModelRole.CHAT,
                "https://api.example.com/v1/",
                "cloud-model",
                "sk-super-secret",
                false
        ));

        ArgumentCaptor<AiModelConfig> savedModel = ArgumentCaptor.forClass(AiModelConfig.class);
        org.mockito.Mockito.verify(repository).save(savedModel.capture());
        String stored = savedModel.getValue().getCredentialEncrypted();
        assertThat(stored).startsWith("v1:").doesNotContain("sk-super-secret");
        assertThat(cipher.decrypt(stored)).isEqualTo("sk-super-secret");
        assertThat(result.hasCredential()).isTrue();

        String responseJson = new ObjectMapper().writeValueAsString(result);
        assertThat(responseJson).contains("\"hasCredential\":true");
        assertThat(responseJson).doesNotContain("sk-super-secret");
        assertThat(responseJson).doesNotContain("credential");
    }

    @Test
    void nullCredentialLeavesExistingValueUnchanged() {
        AiModelConfig existing = existingModel(cipher.encrypt("sk-existing"));
        when(repository.findByBaseUrlAndModelName(
                "https://api.example.com/v1",
                "cloud-model"
        )).thenReturn(Optional.of(existing));

        AiModelConfigDTO result = service.createModel(new AiModelConfigRequestDTO(
                "Renamed model",
                AiModelProviderType.OPENAI_COMPATIBLE_API,
                AiModelRole.CHAT,
                "https://api.example.com/v1",
                "cloud-model",
                null,
                false
        ));

        assertThat(cipher.decrypt(existing.getCredentialEncrypted())).isEqualTo("sk-existing");
        assertThat(result.hasCredential()).isTrue();
    }

    @Test
    void emptyCredentialClearsTheStoredValue() {
        AiModelConfig existing = existingModel(cipher.encrypt("sk-existing"));
        when(repository.findByBaseUrlAndModelName(
                "https://api.example.com/v1",
                "cloud-model"
        )).thenReturn(Optional.of(existing));

        AiModelConfigDTO result = service.createModel(new AiModelConfigRequestDTO(
                "Renamed model",
                AiModelProviderType.OPENAI_COMPATIBLE_API,
                AiModelRole.CHAT,
                "https://api.example.com/v1",
                "cloud-model",
                "",
                false
        ));

        assertThat(existing.getCredentialEncrypted()).isNull();
        assertThat(result.hasCredential()).isFalse();
    }

    @Test
    void rejectsNonHttpEndpoint() {
        assertThatThrownBy(() -> service.createModel(new AiModelConfigRequestDTO(
                "Bad model",
                AiModelProviderType.OPENAI_COMPATIBLE_API,
                AiModelRole.CHAT,
                "file:///tmp/models",
                "cloud-model",
                null,
                false
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base URL must be a valid HTTP or HTTPS endpoint");
    }

    @Test
    void defaultModelIsScopedPerRole() {
        AiModelConfig chatDefault = new AiModelConfig();
        chatDefault.setId(UUID.randomUUID());
        chatDefault.setRole(AiModelRole.CHAT);
        chatDefault.setDefaultModel(true);
        chatDefault.setBaseUrl("http://localhost:11434/v1");
        chatDefault.setModelName("qwen3:8b");

        when(repository.findByBaseUrlAndModelName(
                "http://localhost:11434/v1",
                "nomic-embed-text"
        )).thenReturn(Optional.empty());
        when(repository.findAll()).thenReturn(java.util.List.of(chatDefault));

        AiModelConfigDTO result = service.createModel(new AiModelConfigRequestDTO(
                "Embedder",
                AiModelProviderType.OPENAI_COMPATIBLE_LOCAL,
                AiModelRole.EMBEDDING,
                "http://localhost:11434/v1",
                "nomic-embed-text",
                null,
                true
        ));

        assertThat(result.role()).isEqualTo(AiModelRole.EMBEDDING);
        assertThat(result.defaultModel()).isTrue();
        // Registering an EMBEDDING default must not demote the CHAT default.
        assertThat(chatDefault.isDefaultModel()).isTrue();
    }

    @Test
    void setDefaultModelDemotesOnlySameRolePeers() {
        UUID targetId = UUID.randomUUID();
        AiModelConfig target = new AiModelConfig();
        target.setId(targetId);
        target.setRole(AiModelRole.EMBEDDING);
        target.setEnabled(true);
        target.setDefaultModel(false);

        AiModelConfig otherEmbedding = new AiModelConfig();
        otherEmbedding.setId(UUID.randomUUID());
        otherEmbedding.setRole(AiModelRole.EMBEDDING);
        otherEmbedding.setEnabled(true);
        otherEmbedding.setDefaultModel(true);

        AiModelConfig chatDefault = new AiModelConfig();
        chatDefault.setId(UUID.randomUUID());
        chatDefault.setRole(AiModelRole.CHAT);
        chatDefault.setEnabled(true);
        chatDefault.setDefaultModel(true);

        when(repository.findById(targetId)).thenReturn(Optional.of(target));
        when(repository.findAll())
                .thenReturn(java.util.List.of(target, otherEmbedding, chatDefault));

        AiModelConfigDTO result = service.setDefaultModel(targetId);

        assertThat(result.defaultModel()).isTrue();
        assertThat(target.isDefaultModel()).isTrue();
        assertThat(otherEmbedding.isDefaultModel()).isFalse();  // demoted (same role)
        assertThat(chatDefault.isDefaultModel()).isTrue();       // untouched (different role)
    }

    @Test
    void setDefaultModelRejectsDisabledModel() {
        UUID modelId = UUID.randomUUID();
        AiModelConfig disabled = new AiModelConfig();
        disabled.setId(modelId);
        disabled.setRole(AiModelRole.EMBEDDING);
        disabled.setEnabled(false);
        when(repository.findById(modelId)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.setDefaultModel(modelId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enable the model");
    }

    private AiModelConfig existingModel(String credentialEncrypted) {
        AiModelConfig existing = new AiModelConfig();
        existing.setId(UUID.randomUUID());
        existing.setDisplayName("Existing");
        existing.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_API);
        existing.setBaseUrl("https://api.example.com/v1");
        existing.setModelName("cloud-model");
        existing.setCredentialEncrypted(credentialEncrypted);
        return existing;
    }
}
