package com.job.scheduler.dto;

/** A warning-only observation produced before a workflow revision is activated. */
public record WorkflowPreActivationWarningDTO(
        String category,
        String title,
        String detail,
        String stateName
) {
}
