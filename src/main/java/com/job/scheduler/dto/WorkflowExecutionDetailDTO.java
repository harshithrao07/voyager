package com.job.scheduler.dto;

import java.util.List;

public record WorkflowExecutionDetailDTO(
        WorkflowExecutionSummaryDTO execution,
        List<WorkflowExecutionScopeDTO> scopes
) {
}
