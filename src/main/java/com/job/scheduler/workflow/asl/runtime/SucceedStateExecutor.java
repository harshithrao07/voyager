package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.enums.AslStateType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class SucceedStateExecutor implements StateExecutor {
    private final AslJsonataEvaluator jsonataEvaluator;

    public SucceedStateExecutor(AslJsonataEvaluator jsonataEvaluator) {
        this.jsonataEvaluator = jsonataEvaluator;
    }

    @Override
    public AslStateType supportedType() {
        return AslStateType.SUCCEED;
    }

    @Override
    public StateOutcome execute(
            JsonNode stateDefinition,
            StateExecutionContext context
    ) {
        JsonNode output = stateDefinition.has("Output")
                ? jsonataEvaluator.evaluate(stateDefinition.get("Output"), context)
                : context.input().deepCopy();
        return new StateOutcome.Succeed(output, context.variables().deepCopy());
    }
}
