package com.job.scheduler.workflow.asl.runtime;

import tools.jackson.databind.JsonNode;

public record StateExecutionContext(
        JsonNode input,
        JsonNode variables,
        JsonNode context,
        JsonNode result,
        JsonNode errorOutput
) {
    public StateExecutionContext(
            JsonNode input,
            JsonNode variables,
            JsonNode context
    ) {
        this(input, variables, context, null, null);
    }
}
