package com.job.scheduler.dto;

import java.util.UUID;

/**
 * Read-only AI diagnosis of a failed workflow execution: a plain-English root cause and supporting
 * evidence. The patch field remains in the response for compatibility and is always empty.
 */
public record WorkflowTriageResponseDTO(
        UUID executionId,
        String failingStateName,
        String rootCause,
        String explanation,
        WorkflowTriagePatchDTO patch
) {
}
