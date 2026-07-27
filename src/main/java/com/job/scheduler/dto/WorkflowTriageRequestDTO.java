package com.job.scheduler.dto;

import java.util.UUID;

/** Optional overrides for an AI failure-triage request. */
public record WorkflowTriageRequestDTO(
        /** Model to diagnose with; falls back to the default AI model when null. */
        UUID modelConfigId
) {
}
