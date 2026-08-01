package com.job.scheduler.dto;

import java.util.List;

public record WorkflowAiExplanationResponseDTO(
        String summary,
        List<String> stateDetails,
        List<String> validationIssues
) {
}
