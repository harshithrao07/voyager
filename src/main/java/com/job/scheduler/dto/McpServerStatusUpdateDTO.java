package com.job.scheduler.dto;

import com.job.scheduler.enums.McpServerStatus;
import jakarta.validation.constraints.NotNull;

public record McpServerStatusUpdateDTO(
        @NotNull
        McpServerStatus status
) {
}
