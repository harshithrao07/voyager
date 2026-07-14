package com.job.scheduler.dto;

import com.job.scheduler.enums.FunctionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record FunctionDefinitionRequestDTO(
        @NotBlank
        @Pattern(
                regexp = "^[a-z0-9][a-z0-9-]*$",
                message = "must use lowercase letters, numbers, and hyphens"
        )
        String name,

        String description,

        FunctionStatus status
) {
}
