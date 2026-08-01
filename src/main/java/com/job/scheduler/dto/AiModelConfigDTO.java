package com.job.scheduler.dto;

import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.AiModelRole;
import com.job.scheduler.enums.AiStructuredOutputMode;

import java.util.UUID;

public record AiModelConfigDTO(
        UUID id,
        String displayName,
        AiModelProviderType providerType,
        AiModelRole role,
        String baseUrl,
        String modelName,
        boolean enabled,
        boolean defaultModel,
        boolean hasCredential,
        AiStructuredOutputMode structuredOutputMode
) {
}
