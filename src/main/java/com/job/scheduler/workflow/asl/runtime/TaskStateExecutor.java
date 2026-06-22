package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.enums.AslStateType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class TaskStateExecutor implements StateExecutor {
    private final AslJsonataEvaluator jsonataEvaluator;

    public TaskStateExecutor(AslJsonataEvaluator jsonataEvaluator) {
        this.jsonataEvaluator = jsonataEvaluator;
    }

    @Override
    public AslStateType supportedType() {
        return AslStateType.TASK;
    }

    @Override
    public StateOutcome execute(
            JsonNode stateDefinition,
            StateExecutionContext context
    ) {
        JsonNode arguments = stateDefinition.has("Arguments")
                ? jsonataEvaluator.evaluate(
                        stateDefinition.get("Arguments"),
                        context
                )
                : context.input().deepCopy();
        return new StateOutcome.DispatchTask(
                stateDefinition.get("Resource").stringValue(),
                arguments,
                evaluatePositiveSeconds(
                        stateDefinition.get("TimeoutSeconds"),
                        context,
                        "TimeoutSeconds"
                ),
                evaluatePositiveSeconds(
                        stateDefinition.get("HeartbeatSeconds"),
                        context,
                        "HeartbeatSeconds"
                )
        );
    }

    private Long evaluatePositiveSeconds(
            JsonNode configuredValue,
            StateExecutionContext context,
            String fieldName
    ) {
        if (configuredValue == null) {
            return null;
        }
        JsonNode evaluated = jsonataEvaluator.evaluate(
                configuredValue,
                context
        );
        if (!evaluated.isIntegralNumber() || evaluated.longValue() <= 0) {
            throw new IllegalArgumentException(
                    fieldName + " must evaluate to a positive integer"
            );
        }
        return evaluated.longValue();
    }
}
