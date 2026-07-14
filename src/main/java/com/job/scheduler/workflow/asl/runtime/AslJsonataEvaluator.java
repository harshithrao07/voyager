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
    private static final Object RUNTIME_INITIALIZATION_MONITOR = new Object();
    private static volatile boolean runtimeInitialized;

    private final ObjectMapper objectMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonataMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final long evaluationTimeoutMs;
    private final int maximumDepth;

    public AslJsonataEvaluator(
            ObjectMapper objectMapper,
            @Value("${scheduler.workflow.mapping-timeout-ms:1000}")
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
        ensureRuntimeInitialized();
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
                    "Could not evaluate ASL JSONata expression: "
                            + rootCauseMessage(exception),
                    exception
            );
        }
    }

    /**
     * JSONata4Java initializes parser and visitor classes lazily. Run a
     * representative Choice expression once, without the per-expression
     * timebox, so class loading and JIT warm-up cannot consume the first
     * workflow state's runtime budget.
     */
    private static void ensureRuntimeInitialized() {
        if (runtimeInitialized) {
            return;
        }
        synchronized (RUNTIME_INITIALIZATION_MONITOR) {
            if (runtimeInitialized) {
                return;
            }
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode states =
                        mapper.createObjectNode();
                states.set(
                        "input",
                        mapper.createObjectNode().put("approved", true)
                );
                Expressions expression = Expressions.parse(
                        "$states.input.approved = true"
                );
                expression.getEnvironment().setVariable("$states", states);
                com.fasterxml.jackson.databind.JsonNode result =
                        expression.evaluateSynced(mapper.createObjectNode());
                if (result == null
                        || !result.isBoolean()
                        || !result.booleanValue()) {
                    throw new IllegalStateException(
                            "JSONata warm-up returned an unexpected result"
                    );
                }
                runtimeInitialized = true;
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Could not initialize the ASL JSONata runtime: "
                                + rootCauseMessage(exception),
                        exception
                );
            }
        }
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
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
