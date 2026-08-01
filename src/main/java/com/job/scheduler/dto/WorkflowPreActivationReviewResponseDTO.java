package com.job.scheduler.dto;

import java.util.List;

/** AI review results shown to the user without changing or blocking the workflow definition. */
public record WorkflowPreActivationReviewResponseDTO(
        List<WorkflowPreActivationWarningDTO> warnings
) {
}
