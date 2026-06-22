package com.job.scheduler.workflow.asl.runtime;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

@Component
public class AslVariableAssignmentEvaluator {
    private final AslJsonataEvaluator jsonataEvaluator;
    private final ObjectMapper objectMapper;

    public AslVariableAssignmentEvaluator(
            AslJsonataEvaluator jsonataEvaluator,
            ObjectMapper objectMapper
    ) {
        this.jsonataEvaluator = jsonataEvaluator;
        this.objectMapper = objectMapper;
    }

    public JsonNode apply(
            JsonNode assign,
            StateExecutionContext context
    ) {
        ObjectNode variables = context.variables() == null
                || !context.variables().isObject()
                ? objectMapper.createObjectNode()
                : (ObjectNode) context.variables().deepCopy();
        if (assign == null) {
            return variables;
        }

        ObjectNode evaluatedAssignments = objectMapper.createObjectNode();
        for (Map.Entry<String, JsonNode> assignment : assign.properties()) {
            evaluatedAssignments.set(
                    assignment.getKey(),
                    jsonataEvaluator.evaluate(assignment.getValue(), context)
            );
        }
        evaluatedAssignments.properties().forEach(
                assignment -> variables.set(
                        assignment.getKey(),
                        assignment.getValue()
                )
        );
        return variables;
    }
}
