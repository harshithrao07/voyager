package com.job.scheduler.service;

import com.job.scheduler.dto.AiModelConfigDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.repository.AiModelConfigRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiModelConfigService {
    private final AiModelConfigRepository repository;

    @Value("${langchain4j.open-ai.chat-model.base-url:http://localhost:11434/v1}")
    private String defaultBaseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name:qwen3:8b}")
    private String defaultModelName;

    @Transactional
    public List<AiModelConfigDTO> listEnabledModels() {
        seedDefaultModelIfNeeded();
        return repository.findByEnabledTrueOrderByDefaultModelDescDisplayNameAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public AiModelConfig resolveModel(UUID modelConfigId) {
        seedDefaultModelIfNeeded();
        if (modelConfigId != null) {
            AiModelConfig model = repository.findById(modelConfigId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "AI model config does not exist"
                    ));
            if (!model.isEnabled()) {
                throw new IllegalArgumentException("AI model config is disabled");
            }
            return model;
        }
        return repository.findFirstByEnabledTrueOrderByDefaultModelDescDisplayNameAsc()
                .orElseThrow(() -> new IllegalStateException(
                        "No enabled AI model config is available"
                ));
    }

    private void seedDefaultModelIfNeeded() {
        if (repository.countByEnabledTrue() > 0) {
            return;
        }
        AiModelConfig model = new AiModelConfig();
        model.setDisplayName(defaultModelName);
        model.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_LOCAL);
        model.setBaseUrl(defaultBaseUrl);
        model.setModelName(defaultModelName);
        model.setEnabled(true);
        model.setDefaultModel(true);
        repository.save(model);
    }

    private AiModelConfigDTO toDto(AiModelConfig model) {
        return new AiModelConfigDTO(
                model.getId(),
                model.getDisplayName(),
                model.getProviderType(),
                model.getBaseUrl(),
                model.getModelName(),
                model.isEnabled(),
                model.isDefaultModel()
        );
    }
}
