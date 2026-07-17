package com.job.scheduler.dto;

import com.job.scheduler.enums.AiModelProviderType;
import jakarta.validation.constraints.NotBlank;

public record AiModelDiscoverRequestDTO(
        @NotBlank(message = "Base URL cannot be blank") String baseUrl,
        String credentialRef,
        AiModelProviderType providerType
) {
}
