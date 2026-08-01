package com.job.scheduler.dto;

import com.job.scheduler.enums.AslStateType;
import com.job.scheduler.enums.StateExecutionStatus;

public record WorkflowRunStateSummaryDTO(
        String scopePath,
        long sequenceNumber,
        String stateName,
        AslStateType stateType,
        StateExecutionStatus status,
        String summary
) {
}
