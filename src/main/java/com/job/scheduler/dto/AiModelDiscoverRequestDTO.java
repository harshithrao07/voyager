package com.job.scheduler.dto;

import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.AiModelRole;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record AiModelDiscoverRequestDTO(
        @NotBlank(message = "Base URL cannot be blank") String baseUrl,
        String credential,
        AiModelProviderType providerType,
        AiModelRole role,
        // When present and non-empty, only these model names are onboarded (the "discover + pick"
        // flow). When null/empty, every model reported by the endpoint is onboarded.
        List<String> modelNames
) {
}
