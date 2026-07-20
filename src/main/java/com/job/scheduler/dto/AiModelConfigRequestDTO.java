package com.job.scheduler.dto;

import com.job.scheduler.enums.AiModelProviderType;
import jakarta.validation.constraints.NotBlank;

public record AiModelConfigRequestDTO(
        @NotBlank(message = "Display name cannot be blank") String displayName,
        AiModelProviderType providerType,
        @NotBlank(message = "Base URL cannot be blank") String baseUrl,
        @NotBlank(message = "Model name cannot be blank") String modelName,
        String credential,
        boolean defaultModel
) {
}
