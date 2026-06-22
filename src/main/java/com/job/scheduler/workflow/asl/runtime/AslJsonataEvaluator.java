package com.job.scheduler.workflow.asl.runtime;

import com.api.jsonata4java.expressions.Expressions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

@Component
public class AslJsonataEvaluator {
    private final ObjectMapper objectMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonataMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final long evaluationTimeoutMs;
    private final int maximumDepth;

    public AslJsonataEvaluator(
            ObjectMapper objectMapper,
            @Value("${scheduler.workflow.mapping-timeout-ms:100}")
            long evaluationTimeoutMs,
            @Value("${scheduler.workflow.mapping-max-depth:100}")
            int maximumDepth
    ) {
        if (evaluationTimeoutMs <= 0 || maximumDepth <= 0) {
            throw new IllegalArgumentException("JSONata runtime limits are invalid");
        }
        this.objectMapper = objectMapper;
        this.evaluationTimeoutMs = evaluationTimeoutMs;
        this.maximumDepth = maximumDepth;
    }

    public JsonNode evaluate(
            JsonNode value,
            StateExecutionContext context
    ) {
        if (value == null) {
            return objectMapper.nullNode();
        }
        if (value.isString() && isExpression(value.stringValue())) {
            return evaluateExpression(value.stringValue(), context);
        }
        if (value.isObject()) {
            ObjectNode evaluated = objectMapper.createObjectNode();
            value.properties().forEach(entry -> evaluated.set(
                    entry.getKey(),
                    evaluate(entry.getValue(), context)
            ));
            return evaluated;
        }
        if (value.isArray()) {
            ArrayNode evaluated = objectMapper.createArrayNode();
            value.forEach(element -> evaluated.add(evaluate(element, context)));
            return evaluated;
        }
        return value.deepCopy();
    }

    private JsonNode evaluateExpression(
            String delimitedExpression,
            StateExecutionContext context
    ) {
        String expression = delimitedExpression.trim();
        expression = expression.substring(2, expression.length() - 2).trim();
        try {
            Expressions parsed = Expressions.parse(expression);
            parsed.getEnvironment().setVariable(
                    "$states",
                    toJsonataNode(statesValue(context))
            );
            if (context.variables() != null && context.variables().isObject()) {
                for (Map.Entry<String, JsonNode> variable
                        : context.variables().properties()) {
                    parsed.getEnvironment().setVariable(
                            "$" + variable.getKey(),
                            toJsonataNode(variable.getValue())
                    );
                }
            }

            com.fasterxml.jackson.databind.JsonNode result =
                    parsed.evaluateSynced(
                            toJsonataNode(context.input()),
                            evaluationTimeoutMs,
                            maximumDepth
                    );
            if (result == null) {
                return objectMapper.nullNode();
            }
            return objectMapper.readTree(
                    jsonataMapper.writeValueAsString(result)
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Could not evaluate ASL JSONata expression",
                    exception
            );
        }
    }

    private JsonNode statesValue(StateExecutionContext context) {
        ObjectNode states = objectMapper.createObjectNode();
        states.set("input", nullToJson(context.input()));
        states.set("result", nullToJson(context.result()));
        states.set("errorOutput", nullToJson(context.errorOutput()));
        states.set("context", nullToJson(context.context()));
        return states;
    }

    private JsonNode nullToJson(JsonNode value) {
        return value == null ? objectMapper.nullNode() : value;
    }

    private com.fasterxml.jackson.databind.JsonNode toJsonataNode(JsonNode value)
            throws Exception {
        return jsonataMapper.readTree(
                objectMapper.writeValueAsString(nullToJson(value))
        );
    }

    private boolean isExpression(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("{%") && trimmed.endsWith("%}");
    }
}
