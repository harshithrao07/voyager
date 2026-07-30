package com.job.scheduler.dto;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Legacy failure-triage patch response retained for API compatibility. Diagnosis-only triage always
 * returns {@link #none()} and never proposes workflow changes.
 *
 * @param hasPatch          whether the model returned a corrected definition
 * @param aslDefinition     the full corrected ASL, or null when the model proposed no change
 * @param changes           short human-readable summaries of what the patch alters
 * @param valid             whether the patched definition passes ASL validation
 * @param validationIssues  validation messages when {@code valid} is false
 */
public record WorkflowTriagePatchDTO(
        boolean hasPatch,
        JsonNode aslDefinition,
        List<String> changes,
        boolean valid,
        List<String> validationIssues
) {
    public static WorkflowTriagePatchDTO none() {
        return new WorkflowTriagePatchDTO(false, null, List.of(), false, List.of());
    }
}
