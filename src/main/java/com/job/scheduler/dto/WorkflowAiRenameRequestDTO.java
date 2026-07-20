package com.job.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkflowAiRenameRequestDTO(
        @NotBlank
        @Size(max = 120)
        String name
) {
}
