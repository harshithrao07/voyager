package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.enums.AslStateType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class PassStateExecutor implements StateExecutor {
    private final AslJsonataEvaluator jsonataEvaluator;
    private final AslVariableAssignmentEvaluator assignmentEvaluator;

    public PassStateExecutor(
            AslJsonataEvaluator jsonataEvaluator,
            AslVariableAssignmentEvaluator assignmentEvaluator
    ) {
        this.jsonataEvaluator = jsonataEvaluator;
        this.assignmentEvaluator = assignmentEvaluator;
    }

    @Override
    public AslStateType supportedType() {
        return AslStateType.PASS;
    }

    @Override
    public StateOutcome execute(
            JsonNode stateDefinition,
            StateExecutionContext context
    ) {
        JsonNode nextVariables = assignmentEvaluator.apply(
                stateDefinition.get("Assign"),
                context
        );
        JsonNode output = stateDefinition.has("Output")
                ? jsonataEvaluator.evaluate(stateDefinition.get("Output"), context)
                : context.input().deepCopy();

        if (stateDefinition.path("End").asBoolean(false)) {
            return new StateOutcome.Succeed(output, nextVariables);
        }
        return new StateOutcome.Continue(
                stateDefinition.path("Next").stringValue(),
                output,
                nextVariables
        );
    }

}
