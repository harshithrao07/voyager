package com.job.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WorkflowGenerationRequestDTO {
    @NotBlank(message = "Instruction cannot be empty")
    private String instruction;
}
