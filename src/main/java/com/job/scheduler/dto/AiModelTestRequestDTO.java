package com.job.scheduler.dto;

import com.job.scheduler.enums.AiModelRole;
import jakarta.validation.constraints.NotBlank;

public record AiModelTestRequestDTO(
        @NotBlank(message = "Base URL cannot be blank") String baseUrl,
        String modelName,
        String credential,
        AiModelRole role
) {
}
