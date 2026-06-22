package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.enums.AslStateType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class FailStateExecutor implements StateExecutor {
    private final AslJsonataEvaluator jsonataEvaluator;

    public FailStateExecutor(AslJsonataEvaluator jsonataEvaluator) {
        this.jsonataEvaluator = jsonataEvaluator;
    }

    @Override
    public AslStateType supportedType() {
        return AslStateType.FAIL;
    }

    @Override
    public StateOutcome execute(
            JsonNode stateDefinition,
            StateExecutionContext context
    ) {
        return new StateOutcome.Fail(
                evaluateOptionalString(
                        stateDefinition.get("Error"),
                        context,
                        "Fail Error"
                ),
                evaluateOptionalString(
                        stateDefinition.get("Cause"),
                        context,
                        "Fail Cause"
                )
        );
    }

    private String evaluateOptionalString(
            JsonNode value,
            StateExecutionContext context,
            String label
    ) {
        if (value == null) {
            return null;
        }
        JsonNode evaluated = jsonataEvaluator.evaluate(value, context);
        if (!evaluated.isString()) {
            throw new IllegalArgumentException(
                    label + " must evaluate to a string"
            );
        }
        return evaluated.stringValue();
    }
}
