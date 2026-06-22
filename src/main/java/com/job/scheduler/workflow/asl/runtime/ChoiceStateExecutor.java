package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.enums.AslStateType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ChoiceStateExecutor implements StateExecutor {
    private final AslJsonataEvaluator jsonataEvaluator;
    private final AslVariableAssignmentEvaluator assignmentEvaluator;

    public ChoiceStateExecutor(
            AslJsonataEvaluator jsonataEvaluator,
            AslVariableAssignmentEvaluator assignmentEvaluator
    ) {
        this.jsonataEvaluator = jsonataEvaluator;
        this.assignmentEvaluator = assignmentEvaluator;
    }

    @Override
    public AslStateType supportedType() {
        return AslStateType.CHOICE;
    }

    @Override
    public StateOutcome execute(
            JsonNode stateDefinition,
            StateExecutionContext context
    ) {
        for (JsonNode rule : stateDefinition.path("Choices")) {
            JsonNode condition = jsonataEvaluator.evaluate(
                    rule.get("Condition"),
                    context
            );
            if (!condition.isBoolean()) {
                throw new IllegalArgumentException(
                        "Choice Condition must evaluate to a boolean"
                );
            }
            if (condition.booleanValue()) {
                return transition(rule, context);
            }
        }

        if (!stateDefinition.has("Default")) {
            return new StateOutcome.Fail(
                    "States.NoChoiceMatched",
                    "Choice state did not match a rule and has no Default"
            );
        }

        JsonNode variables = assignmentEvaluator.apply(
                stateDefinition.get("Assign"),
                context
        );
        JsonNode output = stateDefinition.has("Output")
                ? jsonataEvaluator.evaluate(stateDefinition.get("Output"), context)
                : context.input().deepCopy();
        return new StateOutcome.Continue(
                stateDefinition.get("Default").stringValue(),
                output,
                variables
        );
    }

    private StateOutcome transition(
            JsonNode rule,
            StateExecutionContext context
    ) {
        JsonNode variables = assignmentEvaluator.apply(
                rule.get("Assign"),
                context
        );
        JsonNode output = rule.has("Output")
                ? jsonataEvaluator.evaluate(rule.get("Output"), context)
                : context.input().deepCopy();
        return new StateOutcome.Continue(
                rule.get("Next").stringValue(),
                output,
                variables
        );
    }
}
