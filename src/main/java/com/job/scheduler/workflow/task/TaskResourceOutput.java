package com.job.scheduler.workflow.task;

import com.job.scheduler.dto.StepResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Normalizes a handler {@link StepResult} into a non-null Task output node. */
final class TaskResourceOutput {

    static JsonNode of(StepResult result) {
        if (result == null || result.output() == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        return result.output();
    }

    private TaskResourceOutput() {
    }
}
