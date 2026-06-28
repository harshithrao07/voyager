package com.job.scheduler.dto;

import jakarta.validation.constraints.NotBlank;

public record AiModelDiscoverRequestDTO(
        @NotBlank(message = "Base URL cannot be blank") String baseUrl,
        String apiKey
) {
}
