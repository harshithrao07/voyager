package com.job.scheduler.dto;

import java.util.UUID;

/**
 * AI diagnosis of a failed workflow execution: a plain-English root cause and explanation, plus an
 * optional validated ASL patch the user can open in the editor.
 */
public record WorkflowTriageResponseDTO(
        UUID executionId,
        String failingStateName,
        String rootCause,
        String explanation,
        WorkflowTriagePatchDTO patch
) {
}
