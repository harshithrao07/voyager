package com.job.scheduler.dto;

import java.util.UUID;

public record WorkflowAiRegenerateRequestDTO(
        UUID modelConfigId
) {
}
