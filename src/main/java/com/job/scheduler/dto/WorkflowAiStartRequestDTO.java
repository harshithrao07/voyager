package com.job.scheduler.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record WorkflowAiStartRequestDTO(
        @NotBlank(message = "Instruction cannot be empty") String instruction,
        UUID modelConfigId,
        String userDateTime
) {
}
