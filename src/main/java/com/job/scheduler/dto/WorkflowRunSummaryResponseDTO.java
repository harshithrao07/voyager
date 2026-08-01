package com.job.scheduler.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowRunSummaryResponseDTO(
        UUID executionId,
        String headline,
        String overview,
        String outcome,
        List<WorkflowRunStateSummaryDTO> states,
        Instant generatedAt
) {
}
