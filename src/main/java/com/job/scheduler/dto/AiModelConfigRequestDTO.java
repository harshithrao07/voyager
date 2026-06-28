package com.job.scheduler.dto;

import jakarta.validation.constraints.NotBlank;

public record AiModelConfigRequestDTO(
        @NotBlank(message = "Display name cannot be blank") String displayName,
        @NotBlank(message = "Base URL cannot be blank") String baseUrl,
        @NotBlank(message = "Model name cannot be blank") String modelName,
        String apiKey,
        boolean defaultModel
) {
}
