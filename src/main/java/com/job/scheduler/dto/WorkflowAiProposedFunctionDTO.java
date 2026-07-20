package com.job.scheduler.dto;

import java.util.List;

/**
 * A function the workflow model proposes to create because no catalog entry covers a requested
 * action. The user reviews (and may edit) the code before approving; on approval Voyager creates,
 * publishes, and activates it through the normal function lifecycle so it enters the Task catalog.
 */
public record WorkflowAiProposedFunctionDTO(
        String name,
        String description,
        Integer languageId,
        String sourceCode,
        List<FunctionTestCaseDTO> testCases,
        String rationale
) {
}
