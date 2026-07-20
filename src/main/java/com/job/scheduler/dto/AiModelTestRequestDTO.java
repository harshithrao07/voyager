package com.job.scheduler.dto;

import jakarta.validation.constraints.NotBlank;

public record AiModelTestRequestDTO(
        @NotBlank(message = "Base URL cannot be blank") String baseUrl,
        String modelName,
        String credential
) {
}
