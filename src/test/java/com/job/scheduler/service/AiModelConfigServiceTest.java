package com.job.scheduler.service;

import com.job.scheduler.dto.AiModelConfigDTO;
import com.job.scheduler.dto.AiModelConfigRequestDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
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
    @Mock
    private AiModelConfigRepository repository;
    @Mock
    private SecretResolver secretResolver;

    private AiModelConfigService service;

    @BeforeEach
    void setUp() {
        service = new AiModelConfigService(repository, new ObjectMapper(), secretResolver);
        lenient().when(repository.save(any(AiModelConfig.class))).thenAnswer(invocation -> {
            AiModelConfig model = invocation.getArgument(0);
            if (model.getId() == null) {
                model.setId(UUID.randomUUID());
            }
            return model;
        });
    }

    @Test
    void createsCloudModelWithCredentialReferenceOnly() throws Exception {
        when(repository.findByBaseUrlAndModelName(
                "https://api.example.com/v1",
                "cloud-model"
        )).thenReturn(Optional.empty());

        AiModelConfigDTO result = service.createModel(new AiModelConfigRequestDTO(
                "Cloud model",
                AiModelProviderType.OPENAI_COMPATIBLE_API,
                "https://api.example.com/v1/",
                "cloud-model",
                "OPENAI_API_KEY",
                false
        ));

        ArgumentCaptor<AiModelConfig> savedModel = ArgumentCaptor.forClass(
                AiModelConfig.class
        );
        org.mockito.Mockito.verify(repository).save(savedModel.capture());
        assertThat(savedModel.getValue().getProviderType())
                .isEqualTo(AiModelProviderType.OPENAI_COMPATIBLE_API);
        assertThat(savedModel.getValue().getBaseUrl())
                .isEqualTo("https://api.example.com/v1");
        assertThat(savedModel.getValue().getCredentialRef()).isEqualTo("OPENAI_API_KEY");
        assertThat(result.credentialRef()).isEqualTo("OPENAI_API_KEY");
        assertThat(result.hasCredential()).isTrue();

        String responseJson = new ObjectMapper().writeValueAsString(result);
        assertThat(responseJson).contains("\"hasCredential\":true");
        assertThat(responseJson).contains("OPENAI_API_KEY");
        assertThat(responseJson).doesNotContain("apiKey");
    }

    @Test
    void preservesExistingCredentialReferenceWhenUpdateOmitsIt() {
        AiModelConfig existing = new AiModelConfig();
        existing.setId(UUID.randomUUID());
        existing.setDisplayName("Existing");
        existing.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_API);
        existing.setBaseUrl("https://api.example.com/v1");
        existing.setModelName("cloud-model");
        existing.setCredentialRef("OPENAI_API_KEY");
        when(repository.findByBaseUrlAndModelName(
                "https://api.example.com/v1",
                "cloud-model"
        )).thenReturn(Optional.of(existing));

        AiModelConfigDTO result = service.createModel(new AiModelConfigRequestDTO(
                "Renamed model",
                AiModelProviderType.OPENAI_COMPATIBLE_API,
                "https://api.example.com/v1",
                "cloud-model",
                "  ",
                false
        ));

        assertThat(existing.getCredentialRef()).isEqualTo("OPENAI_API_KEY");
        assertThat(result.hasCredential()).isTrue();
    }

    @Test
    void rejectsCredentialValueInsteadOfReference() {
        assertThatThrownBy(() -> service.createModel(new AiModelConfigRequestDTO(
                "Cloud model",
                AiModelProviderType.OPENAI_COMPATIBLE_API,
                "https://api.example.com/v1",
                "cloud-model",
                "sk-plaintext-value",
                false
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Secret reference must use UPPER_SNAKE_CASE");
    }

    @Test
    void rejectsNonHttpEndpoint() {
        assertThatThrownBy(() -> service.createModel(new AiModelConfigRequestDTO(
                "Bad model",
                AiModelProviderType.OPENAI_COMPATIBLE_API,
                "file:///tmp/models",
                "cloud-model",
                null,
                false
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base URL must be a valid HTTP or HTTPS endpoint");
    }
}
