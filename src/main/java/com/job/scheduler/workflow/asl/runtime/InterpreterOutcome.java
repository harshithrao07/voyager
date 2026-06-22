package com.job.scheduler.workflow.asl.runtime;

import tools.jackson.databind.JsonNode;

public sealed interface InterpreterOutcome {

    record Continued(String nextStateName, JsonNode stateOutput)
            implements InterpreterOutcome {
    }

    record Succeeded(JsonNode output) implements InterpreterOutcome {
    }

    record Failed(String error, String cause) implements InterpreterOutcome {
    }

    record Waiting(java.time.Instant wakeAt) implements InterpreterOutcome {
    }

    record Dispatched(java.util.UUID stateExecutionAttemptId)
            implements InterpreterOutcome {
    }

    record RetryScheduled(
            java.util.UUID stateExecutionAttemptId,
            java.time.Instant availableAt
    ) implements InterpreterOutcome {
    }

    /**
     * A compound state (Parallel/Map) created child scopes that the driver must
     * advance. The parent scope is now waiting on those children.
     */
    record Forked(
            java.util.UUID parentScopeId,
            java.util.List<java.util.UUID> childScopeIds
    ) implements InterpreterOutcome {
    }

    /**
     * A compound parent scope is waiting on children that have not all settled.
     * Unlike {@link Waiting} this has no wake time; it is resumed by child
     * settlement, not by the wait scheduler.
     */
    record Joining(java.util.UUID parentScopeId) implements InterpreterOutcome {
    }
}
