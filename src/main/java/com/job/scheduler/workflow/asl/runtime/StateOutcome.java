package com.job.scheduler.workflow.asl.runtime;

import tools.jackson.databind.JsonNode;

public sealed interface StateOutcome {

    record Continue(
            String nextStateName,
            JsonNode output,
            JsonNode variables
    ) implements StateOutcome {
    }

    record Succeed(
            JsonNode output,
            JsonNode variables
    ) implements StateOutcome {
    }

    record Fail(String error, String cause) implements StateOutcome {
    }

    record Waiting(
            String nextStateName,
            JsonNode output,
            JsonNode variables,
            java.time.Instant wakeAt
    ) implements StateOutcome {
    }

    record DispatchTask(
            String resource,
            JsonNode arguments,
            Long timeoutSeconds,
            Long heartbeatSeconds
    ) implements StateOutcome {
    }
}
