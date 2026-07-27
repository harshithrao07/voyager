package com.job.scheduler.dto;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * A proposed ASL fix from failure triage. The corrected definition is run through the same
 * validators as authoring so the UI can show whether it is safe to open in the editor.
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
