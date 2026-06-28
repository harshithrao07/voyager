package com.job.scheduler.dto;

import com.job.scheduler.enums.AiModelProviderType;

import java.util.UUID;

public record AiModelConfigDTO(
        UUID id,
        String displayName,
        AiModelProviderType providerType,
        String baseUrl,
        String modelName,
        boolean enabled,
        boolean defaultModel
) {
}
