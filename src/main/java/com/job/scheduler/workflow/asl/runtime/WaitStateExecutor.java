package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.enums.AslStateType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;

@Component
public class WaitStateExecutor implements StateExecutor {
    private final AslJsonataEvaluator jsonataEvaluator;
    private final AslVariableAssignmentEvaluator assignmentEvaluator;
    private final Clock clock;

    @Autowired
    public WaitStateExecutor(
            AslJsonataEvaluator jsonataEvaluator,
            AslVariableAssignmentEvaluator assignmentEvaluator
    ) {
        this(jsonataEvaluator, assignmentEvaluator, Clock.systemUTC());
    }

    WaitStateExecutor(
            AslJsonataEvaluator jsonataEvaluator,
            AslVariableAssignmentEvaluator assignmentEvaluator,
            Clock clock
    ) {
        this.jsonataEvaluator = jsonataEvaluator;
        this.assignmentEvaluator = assignmentEvaluator;
        this.clock = clock;
    }

    @Override
    public AslStateType supportedType() {
        return AslStateType.WAIT;
    }

    @Override
    public StateOutcome execute(
            JsonNode stateDefinition,
            StateExecutionContext context
    ) {
        Instant wakeAt = stateDefinition.has("Seconds")
                ? wakeFromSeconds(
                        jsonataEvaluator.evaluate(
                                stateDefinition.get("Seconds"),
                                context
                        )
                )
                : wakeFromTimestamp(
                        jsonataEvaluator.evaluate(
                                stateDefinition.get("Timestamp"),
                                context
                        )
                );
        JsonNode variables = assignmentEvaluator.apply(
                stateDefinition.get("Assign"),
                context
        );
        JsonNode output = stateDefinition.has("Output")
                ? jsonataEvaluator.evaluate(stateDefinition.get("Output"), context)
                : context.input().deepCopy();
        String nextStateName = stateDefinition.path("End").asBoolean(false)
                ? null
                : stateDefinition.get("Next").stringValue();

        return new StateOutcome.Waiting(
                nextStateName,
                output,
                variables,
                wakeAt
        );
    }

    private Instant wakeFromSeconds(JsonNode value) {
        if (!value.isIntegralNumber() || value.longValue() < 0) {
            throw new IllegalArgumentException(
                    "Wait Seconds must evaluate to a non-negative integer"
            );
        }
        return clock.instant().plusSeconds(value.longValue());
    }

    private Instant wakeFromTimestamp(JsonNode value) {
        if (!value.isString()) {
            throw new IllegalArgumentException(
                    "Wait Timestamp must evaluate to an RFC 3339 string"
            );
        }
        try {
            return OffsetDateTime.parse(value.stringValue()).toInstant();
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Wait Timestamp must evaluate to an RFC 3339 string",
                    exception
            );
        }
    }
}
