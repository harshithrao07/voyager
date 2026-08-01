package com.job.scheduler.dto;

import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.AiModelRole;
import jakarta.validation.constraints.NotBlank;

public record AiModelDiscoverRequestDTO(
        @NotBlank(message = "Base URL cannot be blank") String baseUrl,
        String credential,
        AiModelProviderType providerType,
        AiModelRole role
) {
}
