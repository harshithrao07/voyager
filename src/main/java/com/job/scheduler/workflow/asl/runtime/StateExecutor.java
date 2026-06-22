package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.enums.AslStateType;
import tools.jackson.databind.JsonNode;

public interface StateExecutor {
    AslStateType supportedType();

    StateOutcome execute(
            JsonNode stateDefinition,
            StateExecutionContext context
    );
}
