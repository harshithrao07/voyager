package com.job.scheduler.workflow.asl.validation;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AslStateDefinitionValidator {
    private static final Set<String> STATE_TYPES = Set.of(
            "Task",
            "Pass",
            "Choice",
            "Wait",
            "Succeed",
            "Fail",
            "Parallel",
            "Map"
    );
    private static final Set<String> PASS_FIELDS = Set.of(
            "Type",
            "Comment",
            "Assign",
            "Output",
            "Next",
            "End"
    );
    private static final Set<String> TASK_FIELDS = Set.of(
            "Type",
            "Comment",
            "Resource",
            "Arguments",
            "Assign",
            "Output",
            "TimeoutSeconds",
            "HeartbeatSeconds",
            "Retry",
            "Catch",
            "Next",
            "End"
    );
    private static final Set<String> CHOICE_FIELDS = Set.of(
            "Type",
            "Comment",
            "Assign",
            "Output",
            "Choices",
            "Default"
    );
    private static final Set<String> WAIT_FIELDS = Set.of(
            "Type",
            "Comment",
            "Seconds",
            "Timestamp",
            "Assign",
            "Output",
            "Next",
            "End"
    );
    private static final Set<String> PARALLEL_FIELDS = Set.of(
            "Type",
            "Comment",
            "Branches",
            "Arguments",
            "Assign",
            "Output",
            "Retry",
            "Catch",
            "Next",
            "End"
    );
    private static final Set<String> MAP_FIELDS = Set.of(
            "Type",
            "Comment",
            "ItemProcessor",
            "Iterator",
            "ItemReader",
            "Items",
            "ItemSelector",
            "ItemBatcher",
            "ResultWriter",
            "MaxConcurrency",
            "ToleratedFailurePercentage",
            "ToleratedFailureCount",
            "Assign",
            "Output",
            "Retry",
            "Catch",
            "Next",
            "End"
    );
    private static final Set<String> CHOICE_RULE_FIELDS = Set.of(
            "Condition",
            "Next",
            "Assign",
            "Output"
    );
    private static final Set<String> RETRY_FIELDS = Set.of(
            "ErrorEquals",
            "IntervalSeconds",
            "MaxAttempts",
            "BackoffRate",
            "MaxDelaySeconds",
            "JitterStrategy"
    );
    private static final Set<String> CATCH_FIELDS = Set.of(
            "ErrorEquals",
            "Next",
            "Assign",
            "Output"
    );
    private static final Set<String> SUCCEED_FIELDS = Set.of(
            "Type",
            "Comment",
            "Output"
    );
    private static final Set<String> FAIL_FIELDS = Set.of(
            "Type",
            "Comment",
            "Error",
            "Cause"
    );
    private static final Set<String> JSONPATH_ONLY_FIELDS = Set.of(
            "InputPath",
            "OutputPath",
            "Parameters",
            "Result",
            "ResultPath",
            "ResultSelector",
            "ItemsPath",
            "SecondsPath",
            "TimestampPath",
            "MaxConcurrencyPath",
            "MaxItemsPath",
            "MaxItemsPerBatchPath",
            "MaxInputBytesPerBatchPath",
            "ToleratedFailureCountPath",
            "ToleratedFailurePercentagePath",
            "ErrorPath",
            "CausePath"
    );
    private static final Set<String> RESERVED_ERRORS = Set.of(
            "States.ALL",
            "States.Timeout",
            "States.TaskFailed",
            "States.Permissions",
            "States.BranchFailed",
            "States.NoChoiceMatched",
            "States.QueryEvaluationError"
    );
    private static final Set<String> JSONPATH_CHOICE_FIELDS = Set.of(
            "Variable",
            "And",
            "Or",
            "Not",
            "StringEquals",
            "StringEqualsPath",
            "StringLessThan",
            "StringLessThanPath",
            "StringGreaterThan",
            "StringGreaterThanPath",
            "StringLessThanEquals",
            "StringLessThanEqualsPath",
            "StringGreaterThanEquals",
            "StringGreaterThanEqualsPath",
            "StringMatches",
            "NumericEquals",
            "NumericEqualsPath",
            "NumericLessThan",
            "NumericLessThanPath",
            "NumericGreaterThan",
            "NumericGreaterThanPath",
            "NumericLessThanEquals",
            "NumericLessThanEqualsPath",
            "NumericGreaterThanEquals",
            "NumericGreaterThanEqualsPath",
            "BooleanEquals",
            "BooleanEqualsPath",
            "TimestampEquals",
            "TimestampEqualsPath",
            "TimestampLessThan",
            "TimestampLessThanPath",
            "TimestampGreaterThan",
            "TimestampGreaterThanPath",
            "TimestampLessThanEquals",
            "TimestampLessThanEqualsPath",
            "TimestampGreaterThanEquals",
            "TimestampGreaterThanEqualsPath",
            "IsNull",
            "IsPresent",
            "IsNumeric",
            "IsString",
            "IsBoolean",
            "IsTimestamp"
    );
    private static final int MAX_VARIABLE_NAME_CODE_POINTS = 80;
    private final AslJsonataExpressionValidator jsonataExpressionValidator;

    public AslStateDefinitionValidator(
            AslJsonataExpressionValidator jsonataExpressionValidator
    ) {
        this.jsonataExpressionValidator = jsonataExpressionValidator;
    }

    public void validate(
            String stateName,
            JsonNode state,
            JsonNode states,
            String machineLocation,
            NestedMachineValidator nestedMachineValidator,
            List<AslValidationIssue> issues
    ) {
        String location = machineLocation + ".States." + stateName;
        JsonNode typeNode = state.get("Type");
        if (typeNode == null || !typeNode.isString() || typeNode.stringValue().isBlank()) {
            return;
        }

        validateComment(state.get("Comment"), location, issues);
        validateDialectFields(state, location, issues);

        String type = typeNode.stringValue();
        if (!STATE_TYPES.contains(type)) {
            issues.add(issue(
                    location + ".Type",
                    AslValidationCategory.ASL,
                    "UNKNOWN_STATE_TYPE",
                    "Unsupported ASL state Type: " + type
            ));
            return;
        }

        switch (type) {
            case "Task" -> validateTask(state, states, location, issues);
            case "Pass" -> validatePass(state, states, location, issues);
            case "Choice" -> validateChoice(state, states, location, issues);
            case "Wait" -> validateWait(state, states, location, issues);
            case "Succeed" -> validateSucceed(state, location, issues);
            case "Fail" -> validateFail(state, location, issues);
            case "Parallel" -> validateParallel(
                    state,
                    states,
                    location,
                    nestedMachineValidator,
                    issues
            );
            case "Map" -> validateMap(
                    state,
                    states,
                    location,
                    nestedMachineValidator,
                    issues
            );
            default -> {
                // State-specific validation for the remaining ASL types is added in later slices.
            }
        }
    }

    private void validateWait(
            JsonNode state,
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        validateAllowedFields(state, WAIT_FIELDS, location, issues);
        validateNextOrEnd(state, states, location, issues);
        validateAssign(state.get("Assign"), location + ".Assign", false, false, issues);
        validateJsonataValues(
                state.get("Output"),
                location + ".Output",
                false,
                false,
                issues
        );

        boolean hasSeconds = state.has("Seconds");
        boolean hasTimestamp = state.has("Timestamp");
        if (hasSeconds == hasTimestamp) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "WAIT_TIME_EXACTLY_ONE",
                    "Wait state must contain exactly one of Seconds or Timestamp"
            ));
        }
        if (hasSeconds) {
            validateNonNegativeIntegerOrExpression(
                    state.get("Seconds"),
                    location + ".Seconds",
                    issues
            );
        }
        if (hasTimestamp) {
            validateTimestampOrExpression(
                    state.get("Timestamp"),
                    location + ".Timestamp",
                    issues
            );
        }
    }

    private void validateParallel(
            JsonNode state,
            JsonNode states,
            String location,
            NestedMachineValidator nestedMachineValidator,
            List<AslValidationIssue> issues
    ) {
        validateAllowedFields(state, PARALLEL_FIELDS, location, issues);
        validateNextOrEnd(state, states, location, issues);
        validateJsonataValues(
                state.get("Arguments"),
                location + ".Arguments",
                false,
                false,
                issues
        );
        validateAssign(state.get("Assign"), location + ".Assign", true, false, issues);
        validateJsonataValues(
                state.get("Output"),
                location + ".Output",
                true,
                false,
                issues
        );
        validateRetry(state.get("Retry"), location + ".Retry", issues);
        validateCatch(state.get("Catch"), states, location + ".Catch", issues);

        JsonNode branches = state.get("Branches");
        if (branches == null || !branches.isArray() || branches.isEmpty()) {
            issues.add(issue(
                    location + ".Branches",
                    AslValidationCategory.ASL,
                    "INVALID_BRANCHES",
                    "Parallel Branches must be a non-empty array"
            ));
        } else {
            for (int index = 0; index < branches.size(); index++) {
                nestedMachineValidator.validate(
                        branches.get(index),
                        location + ".Branches[" + index + "]",
                        false,
                        issues
                );
            }
        }
    }

    private void validateMap(
            JsonNode state,
            JsonNode states,
            String location,
            NestedMachineValidator nestedMachineValidator,
            List<AslValidationIssue> issues
    ) {
        validateAllowedFields(state, MAP_FIELDS, location, issues);
        validateNextOrEnd(state, states, location, issues);
        validateAssign(state.get("Assign"), location + ".Assign", true, false, issues);
        validateJsonataValues(
                state.get("Output"),
                location + ".Output",
                true,
                false,
                issues
        );
        validateRetry(state.get("Retry"), location + ".Retry", issues);
        validateCatch(state.get("Catch"), states, location + ".Catch", issues);
        validateItemProcessor(
                state.get("ItemProcessor"),
                location + ".ItemProcessor",
                nestedMachineValidator,
                issues
        );
        validateItems(state.get("Items"), location + ".Items", issues);
        validateJsonataValues(
                state.get("ItemSelector"),
                location + ".ItemSelector",
                false,
                false,
                issues
        );
        validateNonNegativeIntegerOrExpression(
                state.get("MaxConcurrency"),
                location + ".MaxConcurrency",
                "MaxConcurrency",
                issues
        );

        if (state.has("Iterator")) {
            issues.add(issue(
                    location + ".Iterator",
                    AslValidationCategory.DIALECT,
                    "DEPRECATED_ITERATOR_NOT_ALLOWED",
                    "Map must use ItemProcessor instead of deprecated Iterator"
            ));
        }

        reportUnsupportedMapFeatures(state, location, issues);
    }

    /**
     * The supported inline Map feature set is Items, ItemSelector,
     * MaxConcurrency, ItemProcessor (Assign, Output, Retry, Catch, Next, End).
     * Advanced features (ItemReader, ItemBatcher, ResultWriter, and the
     * tolerated-failure thresholds) are not implemented and are rejected here, as
     * is any ProcessorConfig Mode other than INLINE (the default).
     */
    private static final Set<String> UNSUPPORTED_MAP_FEATURES = Set.of(
            "ItemReader",
            "ItemBatcher",
            "ResultWriter",
            "ToleratedFailureCount",
            "ToleratedFailurePercentage"
    );

    private void reportUnsupportedMapFeatures(
            JsonNode state,
            String location,
            List<AslValidationIssue> issues
    ) {
        for (String feature : UNSUPPORTED_MAP_FEATURES) {
            if (state.has(feature)) {
                issues.add(issue(
                        location + "." + feature,
                        AslValidationCategory.RUNTIME_SUPPORT,
                        "MAP_FEATURE_RUNTIME_UNSUPPORTED",
                        "Map " + feature + " is not supported"
                ));
            }
        }

        JsonNode mode = state
                .path("ItemProcessor")
                .path("ProcessorConfig")
                .path("Mode");
        if (mode.isString() && !"INLINE".equals(mode.stringValue())) {
            issues.add(issue(
                    location + ".ItemProcessor.ProcessorConfig.Mode",
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "PROCESSOR_MODE_RUNTIME_UNSUPPORTED",
                    "Map ItemProcessor ProcessorConfig Mode "
                            + mode.stringValue() + " is not implemented yet"
            ));
        }
    }

    private void validateItemProcessor(
            JsonNode itemProcessor,
            String location,
            NestedMachineValidator nestedMachineValidator,
            List<AslValidationIssue> issues
    ) {
        if (itemProcessor == null) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "ITEM_PROCESSOR_REQUIRED",
                    "Map requires an ItemProcessor nested machine"
            ));
            return;
        }
        if (itemProcessor.isObject()) {
            JsonNode processorConfig = itemProcessor.get("ProcessorConfig");
            if (processorConfig != null && !processorConfig.isObject()) {
                issues.add(issue(
                        location + ".ProcessorConfig",
                        AslValidationCategory.ASL,
                        "PROCESSOR_CONFIG_NOT_OBJECT",
                        "ProcessorConfig must be a JSON object"
                ));
            }
        }
        nestedMachineValidator.validate(itemProcessor, location, true, issues);
    }

    private void validateItems(
            JsonNode items,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (items == null || items.isArray()) {
            return;
        }
        if (items.isString()) {
            validateRequiredExpression(items, location, false, false, issues);
            return;
        }
        issues.add(issue(
                location,
                AslValidationCategory.ASL,
                "ITEMS_ARRAY_OR_EXPRESSION_REQUIRED",
                "Items must be an array or JSONata expression producing an array"
        ));
    }

    private void validateTask(
            JsonNode state,
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        validateAllowedFields(state, TASK_FIELDS, location, issues);
        validateResource(state.get("Resource"), location + ".Resource", issues);
        validateNextOrEnd(state, states, location, issues);
        validateJsonataValues(
                state.get("Arguments"),
                location + ".Arguments",
                false,
                false,
                issues
        );
        validateAssign(state.get("Assign"), location + ".Assign", true, false, issues);
        validateJsonataValues(
                state.get("Output"),
                location + ".Output",
                true,
                false,
                issues
        );
        validatePositiveIntegerOrExpression(
                state.get("TimeoutSeconds"),
                location + ".TimeoutSeconds",
                issues
        );
        validatePositiveIntegerOrExpression(
                state.get("HeartbeatSeconds"),
                location + ".HeartbeatSeconds",
                issues
        );
        validateRetry(state.get("Retry"), location + ".Retry", issues);
        validateCatch(state.get("Catch"), states, location + ".Catch", issues);
    }

    private void validatePass(
            JsonNode state,
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        validateAllowedFields(state, PASS_FIELDS, location, issues);
        validateNextOrEnd(state, states, location, issues);
        validateAssign(state.get("Assign"), location + ".Assign", false, false, issues);
        validateJsonataValues(
                state.get("Output"),
                location + ".Output",
                false,
                false,
                issues
        );
    }

    private void validateChoice(
            JsonNode state,
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        validateAllowedFields(state, CHOICE_FIELDS, location, issues);
        validateAssign(state.get("Assign"), location + ".Assign", false, false, issues);
        validateJsonataValues(
                state.get("Output"),
                location + ".Output",
                false,
                false,
                issues
        );

        JsonNode choices = state.get("Choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            issues.add(issue(
                    location + ".Choices",
                    AslValidationCategory.ASL,
                    "INVALID_CHOICES",
                    "Choices must be a non-empty array"
            ));
        } else {
            for (int index = 0; index < choices.size(); index++) {
                validateChoiceRule(
                        choices.get(index),
                        states,
                        location + ".Choices[" + index + "]",
                        issues
                );
            }
        }

        JsonNode defaultTarget = state.get("Default");
        if (defaultTarget != null) {
            validateTransitionTarget(
                    defaultTarget,
                    states,
                    location + ".Default",
                    "Default",
                    issues
            );
        }
    }

    private void validateChoiceRule(
            JsonNode rule,
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (rule == null || !rule.isObject()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "CHOICE_RULE_NOT_OBJECT",
                    "Choice rule must be a JSON object"
            ));
            return;
        }

        for (Map.Entry<String, JsonNode> property : rule.properties()) {
            String fieldName = property.getKey();
            if (JSONPATH_CHOICE_FIELDS.contains(fieldName)) {
                issues.add(issue(
                        location + "." + fieldName,
                        AslValidationCategory.DIALECT,
                        "JSONPATH_CHOICE_NOT_ALLOWED",
                        "JSONPath Choice operators are not allowed in the JSONata dialect"
                ));
            } else if (!CHOICE_RULE_FIELDS.contains(fieldName)) {
                issues.add(issue(
                        location + "." + fieldName,
                        AslValidationCategory.ASL,
                        "CHOICE_RULE_FIELD_NOT_ALLOWED",
                        fieldName + " is not allowed on a JSONata Choice rule"
                ));
            }
        }

        validateRequiredExpression(
                rule.get("Condition"),
                location + ".Condition",
                false,
                false,
                issues
        );
        validateTransitionTarget(
                rule.get("Next"),
                states,
                location + ".Next",
                "Choice rule Next",
                issues
        );
        validateAssign(rule.get("Assign"), location + ".Assign", false, false, issues);
        validateJsonataValues(
                rule.get("Output"),
                location + ".Output",
                false,
                false,
                issues
        );
    }

    private void validateSucceed(
            JsonNode state,
            String location,
            List<AslValidationIssue> issues
    ) {
        validateAllowedFields(state, SUCCEED_FIELDS, location, issues);
        validateJsonataValues(
                state.get("Output"),
                location + ".Output",
                false,
                false,
                issues
        );
    }

    private void validateFail(
            JsonNode state,
            String location,
            List<AslValidationIssue> issues
    ) {
        validateAllowedFields(state, FAIL_FIELDS, location, issues);
        validateStringOrExpression(state.get("Error"), location + ".Error", issues);
        validateStringOrExpression(state.get("Cause"), location + ".Cause", issues);

        JsonNode error = state.get("Error");
        if (error != null && error.isString()
                && !jsonataExpressionValidator.isExpression(error.stringValue())) {
            String errorName = error.stringValue();
            if (errorName.startsWith("States.") && !RESERVED_ERRORS.contains(errorName)) {
                issues.add(issue(
                        location + ".Error",
                        AslValidationCategory.ASL,
                        "INVALID_RESERVED_ERROR",
                        "Unknown error names must not use the reserved States. prefix"
                ));
            }
        }
    }

    private void validateComment(
            JsonNode comment,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (comment != null && !comment.isString()) {
            issues.add(issue(
                    location + ".Comment",
                    AslValidationCategory.ASL,
                    "STATE_COMMENT_NOT_STRING",
                    "State Comment must be a string"
            ));
        }
    }

    private void validateDialectFields(
            JsonNode state,
            String location,
            List<AslValidationIssue> issues
    ) {
        for (Map.Entry<String, JsonNode> property : state.properties()) {
            String fieldName = property.getKey();
            if ("QueryLanguage".equals(fieldName)) {
                issues.add(issue(
                        location + ".QueryLanguage",
                        AslValidationCategory.DIALECT,
                        "STATE_QUERY_LANGUAGE_NOT_ALLOWED",
                        "Per-state QueryLanguage overrides are not allowed"
                ));
            } else if (JSONPATH_ONLY_FIELDS.contains(fieldName) || fieldName.endsWith(".$")) {
                issues.add(issue(
                        location + "." + fieldName,
                        AslValidationCategory.DIALECT,
                        "JSONPATH_FIELD_NOT_ALLOWED",
                        "JSONPath-only field is not allowed in the scheduler JSONata dialect: " + fieldName
                ));
            }
        }
    }

    private void validateAllowedFields(
            JsonNode state,
            Set<String> allowedFields,
            String location,
            List<AslValidationIssue> issues
    ) {
        for (Map.Entry<String, JsonNode> property : state.properties()) {
            String fieldName = property.getKey();
            if (allowedFields.contains(fieldName)
                    || "QueryLanguage".equals(fieldName)
                    || JSONPATH_ONLY_FIELDS.contains(fieldName)
                    || fieldName.endsWith(".$")) {
                continue;
            }
            issues.add(issue(
                    location + "." + fieldName,
                    AslValidationCategory.ASL,
                    "STATE_FIELD_NOT_ALLOWED",
                    fieldName + " is not allowed on this state type"
            ));
        }
    }

    private void validateNextOrEnd(
            JsonNode state,
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        boolean hasNext = state.has("Next");
        boolean hasEnd = state.has("End");
        if (hasNext == hasEnd) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "NEXT_END_EXACTLY_ONE",
                    "State must contain exactly one of Next or End"
            ));
        }

        if (hasNext) {
            JsonNode next = state.get("Next");
            if (next == null || !next.isString() || next.stringValue().isBlank()) {
                issues.add(issue(
                        location + ".Next",
                        AslValidationCategory.ASL,
                        "INVALID_NEXT",
                        "Next must be a nonblank string"
                ));
            } else if (!states.has(next.stringValue())) {
                issues.add(issue(
                        location + ".Next",
                        AslValidationCategory.ASL,
                        "NEXT_STATE_NOT_FOUND",
                        "Next must name a state in the same States object"
                ));
            }
        }

        if (hasEnd) {
            JsonNode end = state.get("End");
            if (end == null || !end.isBoolean() || !end.booleanValue()) {
                issues.add(issue(
                        location + ".End",
                        AslValidationCategory.ASL,
                        "INVALID_END",
                        "End must be the boolean value true"
                ));
            }
        }
    }

    private void validateAssign(
            JsonNode assign,
            String location,
            boolean allowResult,
            boolean allowErrorOutput,
            List<AslValidationIssue> issues
    ) {
        if (assign == null) {
            return;
        }
        if (!assign.isObject()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "ASSIGN_NOT_OBJECT",
                    "Assign must be a JSON object"
            ));
            return;
        }

        for (Map.Entry<String, JsonNode> assignment : assign.properties()) {
            String variableName = assignment.getKey();
            String variableLocation = location + "." + variableName;
            if ("states".equals(variableName)) {
                issues.add(issue(
                        variableLocation,
                        AslValidationCategory.DIALECT,
                        "RESERVED_VARIABLE_NAME",
                        "The variable name states is reserved"
                ));
            }
            if (!isValidVariableName(variableName)) {
                issues.add(issue(
                        variableLocation,
                        AslValidationCategory.ASL,
                        "INVALID_VARIABLE_NAME",
                        "Variable names must be Unicode identifiers of at most 80 characters"
                ));
            }
            validateJsonataValues(
                    assignment.getValue(),
                    variableLocation,
                    allowResult,
                    allowErrorOutput,
                    issues
            );
        }
    }

    private void validateResource(
            JsonNode resource,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (resource == null || !resource.isString() || resource.stringValue().isBlank()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "INVALID_RESOURCE",
                    "Task Resource must be a nonblank URI"
            ));
            return;
        }

        try {
            URI uri = new URI(resource.stringValue());
            if (uri.getScheme() == null || uri.getScheme().isBlank()
                    || uri.getSchemeSpecificPart() == null
                    || uri.getSchemeSpecificPart().isBlank()) {
                throw new URISyntaxException(resource.stringValue(), "URI requires a scheme and value");
            }
        } catch (URISyntaxException exception) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "INVALID_RESOURCE",
                    "Task Resource must be a valid URI"
            ));
        }
    }

    private void validatePositiveIntegerOrExpression(
            JsonNode value,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (value == null) {
            return;
        }
        if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0) {
            return;
        }
        if (value.isString()) {
            validateRequiredExpression(value, location, false, false, issues);
            return;
        }
        issues.add(issue(
                location,
                AslValidationCategory.ASL,
                "POSITIVE_INTEGER_OR_EXPRESSION_REQUIRED",
                "Field must be a positive integer or JSONata expression producing one"
        ));
    }

    private void validateNonNegativeIntegerOrExpression(
            JsonNode value,
            String location,
            List<AslValidationIssue> issues
    ) {
        validateNonNegativeIntegerOrExpression(value, location, "Seconds", issues);
    }

    private void validateNonNegativeIntegerOrExpression(
            JsonNode value,
            String location,
            String label,
            List<AslValidationIssue> issues
    ) {
        if (value == null) {
            return;
        }
        if (value != null && value.isIntegralNumber()
                && value.canConvertToInt() && value.intValue() >= 0) {
            return;
        }
        if (value != null && value.isString()) {
            validateRequiredExpression(value, location, false, false, issues);
            return;
        }
        issues.add(issue(
                location,
                AslValidationCategory.ASL,
                "NON_NEGATIVE_INTEGER_OR_EXPRESSION_REQUIRED",
                label + " must be a non-negative integer or JSONata expression producing one"
        ));
    }

    private void validateTimestampOrExpression(
            JsonNode value,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "TIMESTAMP_OR_EXPRESSION_REQUIRED",
                    "Timestamp must be an RFC 3339 string or JSONata expression producing one"
            ));
            return;
        }
        if (jsonataExpressionValidator.isExpression(value.stringValue())) {
            jsonataExpressionValidator.validate(
                    value.stringValue(),
                    location,
                    false,
                    false,
                    issues
            );
            return;
        }

        String timestamp = value.stringValue();
        if (timestamp.indexOf('t') >= 0 || timestamp.endsWith("z")) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "INVALID_TIMESTAMP",
                    "Timestamp must use uppercase T and uppercase Z"
            ));
            return;
        }
        try {
            OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException exception) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "INVALID_TIMESTAMP",
                    "Timestamp must be a valid RFC 3339 timestamp"
            ));
        }
    }

    private void reportRuntimeUnsupported(
            String type,
            String location,
            List<AslValidationIssue> issues
    ) {
        issues.add(issue(
                location,
                AslValidationCategory.RUNTIME_SUPPORT,
                "STATE_RUNTIME_UNSUPPORTED",
                type + " state execution is not implemented yet"
        ));
    }

    private void validateRetry(
            JsonNode retry,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (retry == null) {
            return;
        }
        if (!retry.isArray()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "RETRY_NOT_ARRAY",
                    "Retry must be an array"
            ));
            return;
        }
        if (retry.isEmpty()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "RETRY_EMPTY",
                    "Retry must contain at least one retrier"
            ));
            return;
        }

        for (int index = 0; index < retry.size(); index++) {
            JsonNode retrier = retry.get(index);
            String retrierLocation = location + "[" + index + "]";
            if (retrier == null || !retrier.isObject()) {
                issues.add(issue(
                        retrierLocation,
                        AslValidationCategory.ASL,
                        "RETRIER_NOT_OBJECT",
                        "Each Retry entry must be an object"
                ));
                continue;
            }
            validateObjectFields(retrier, RETRY_FIELDS, retrierLocation, "RETRY_FIELD_NOT_ALLOWED", issues);
            boolean matchesAll = validateErrorEquals(
                    retrier.get("ErrorEquals"),
                    retrierLocation + ".ErrorEquals",
                    issues
            );
            if (matchesAll && index != retry.size() - 1) {
                issues.add(issue(
                        retrierLocation + ".ErrorEquals",
                        AslValidationCategory.ASL,
                        "STATES_ALL_NOT_LAST",
                        "A retrier matching States.ALL must be last"
                ));
            }
            validatePositiveInteger(
                    retrier.get("IntervalSeconds"),
                    retrierLocation + ".IntervalSeconds",
                    issues
            );
            validateNonNegativeInteger(
                    retrier.get("MaxAttempts"),
                    retrierLocation + ".MaxAttempts",
                    issues
            );
            validateNumberAtLeastOne(
                    retrier.get("BackoffRate"),
                    retrierLocation + ".BackoffRate",
                    issues
            );
            validatePositiveInteger(
                    retrier.get("MaxDelaySeconds"),
                    retrierLocation + ".MaxDelaySeconds",
                    issues
            );
            validateOptionalNonblankString(
                    retrier.get("JitterStrategy"),
                    retrierLocation + ".JitterStrategy",
                    issues
            );
            if (retrier.has("JitterStrategy")
                    && retrier.get("JitterStrategy").isString()
                    && !"FULL".equals(
                            retrier.get("JitterStrategy").stringValue()
                    )) {
                issues.add(issue(
                        retrierLocation + ".JitterStrategy",
                        AslValidationCategory.RUNTIME_SUPPORT,
                        "JITTER_STRATEGY_RUNTIME_UNSUPPORTED",
                        "Only FULL JitterStrategy is supported"
                ));
            }
        }
    }

    private void validateCatch(
            JsonNode catchers,
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (catchers == null) {
            return;
        }
        if (!catchers.isArray()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "CATCH_NOT_ARRAY",
                    "Catch must be an array"
            ));
            return;
        }
        if (catchers.isEmpty()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "CATCH_EMPTY",
                    "Catch must contain at least one catcher"
            ));
            return;
        }

        for (int index = 0; index < catchers.size(); index++) {
            JsonNode catcher = catchers.get(index);
            String catcherLocation = location + "[" + index + "]";
            if (catcher == null || !catcher.isObject()) {
                issues.add(issue(
                        catcherLocation,
                        AslValidationCategory.ASL,
                        "CATCHER_NOT_OBJECT",
                        "Each Catch entry must be an object"
                ));
                continue;
            }
            validateObjectFields(catcher, CATCH_FIELDS, catcherLocation, "CATCH_FIELD_NOT_ALLOWED", issues);
            boolean matchesAll = validateErrorEquals(
                    catcher.get("ErrorEquals"),
                    catcherLocation + ".ErrorEquals",
                    issues
            );
            if (matchesAll && index != catchers.size() - 1) {
                issues.add(issue(
                        catcherLocation + ".ErrorEquals",
                        AslValidationCategory.ASL,
                        "STATES_ALL_NOT_LAST",
                        "A catcher matching States.ALL must be last"
                ));
            }
            validateTransitionTarget(
                    catcher.get("Next"),
                    states,
                    catcherLocation + ".Next",
                    "Catcher Next",
                    issues
            );
            validateAssign(
                    catcher.get("Assign"),
                    catcherLocation + ".Assign",
                    false,
                    true,
                    issues
            );
            validateJsonataValues(
                    catcher.get("Output"),
                    catcherLocation + ".Output",
                    false,
                    true,
                    issues
            );
        }
    }

    private boolean validateErrorEquals(
            JsonNode errorEquals,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (errorEquals == null || !errorEquals.isArray() || errorEquals.isEmpty()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "INVALID_ERROR_EQUALS",
                    "ErrorEquals must be a non-empty array of error names"
            ));
            return false;
        }

        boolean matchesAll = false;
        for (int index = 0; index < errorEquals.size(); index++) {
            JsonNode errorName = errorEquals.get(index);
            if (errorName == null || !errorName.isString() || errorName.stringValue().isBlank()) {
                issues.add(issue(
                        location + "[" + index + "]",
                        AslValidationCategory.ASL,
                        "INVALID_ERROR_NAME",
                        "Error name must be a nonblank string"
                ));
                continue;
            }
            if ("States.ALL".equals(errorName.stringValue())) {
                matchesAll = true;
            } else if (errorName.stringValue().startsWith("States.")
                    && !RESERVED_ERRORS.contains(errorName.stringValue())) {
                issues.add(issue(
                        location + "[" + index + "]",
                        AslValidationCategory.ASL,
                        "INVALID_RESERVED_ERROR",
                        "Unknown error names must not use the reserved States. prefix"
                ));
            }
        }
        if (matchesAll && errorEquals.size() != 1) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "STATES_ALL_MUST_BE_ALONE",
                    "States.ALL must appear alone in ErrorEquals"
            ));
        }
        return matchesAll;
    }

    private void validateObjectFields(
            JsonNode object,
            Set<String> allowedFields,
            String location,
            String code,
            List<AslValidationIssue> issues
    ) {
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            if (!allowedFields.contains(property.getKey())) {
                issues.add(issue(
                        location + "." + property.getKey(),
                        AslValidationCategory.ASL,
                        code,
                        property.getKey() + " is not allowed here"
                ));
            }
        }
    }

    private void validateTransitionTarget(
            JsonNode target,
            JsonNode states,
            String location,
            String label,
            List<AslValidationIssue> issues
    ) {
        if (target == null || !target.isString() || target.stringValue().isBlank()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "INVALID_TRANSITION_TARGET",
                    label + " must be a nonblank string"
            ));
        } else if (!states.has(target.stringValue())) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "TRANSITION_TARGET_NOT_FOUND",
                    label + " must name a state in the same States object"
            ));
        }
    }

    private void validateRequiredExpression(
            JsonNode value,
            String location,
            boolean allowResult,
            boolean allowErrorOutput,
            List<AslValidationIssue> issues
    ) {
        if (value == null || !value.isString()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.DIALECT,
                    "JSONATA_EXPRESSION_REQUIRED",
                    "Field must be a JSONata expression delimited by {% and %}"
            ));
            return;
        }
        jsonataExpressionValidator.validateRequired(
                value.stringValue(),
                location,
                allowResult,
                allowErrorOutput,
                issues
        );
    }

    private void validatePositiveInteger(
            JsonNode value,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (value != null && (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0)) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "POSITIVE_INTEGER_REQUIRED",
                    "Field must be a positive integer"
            ));
        }
    }

    private void validateNonNegativeInteger(
            JsonNode value,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (value != null && (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "NON_NEGATIVE_INTEGER_REQUIRED",
                    "Field must be a non-negative integer"
            ));
        }
    }

    private void validateNumberAtLeastOne(
            JsonNode value,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (value != null && (!value.isNumber() || value.doubleValue() < 1.0)) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "NUMBER_AT_LEAST_ONE_REQUIRED",
                    "Field must be a number greater than or equal to 1.0"
            ));
        }
    }

    private void validateOptionalNonblankString(
            JsonNode value,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (value != null && (!value.isString() || value.stringValue().isBlank())) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "NONBLANK_STRING_REQUIRED",
                    "Field must be a nonblank string"
            ));
        }
    }

    private boolean isValidVariableName(String variableName) {
        if (variableName.isBlank()
                || variableName.codePointCount(0, variableName.length()) > MAX_VARIABLE_NAME_CODE_POINTS) {
            return false;
        }

        int offset = 0;
        int first = variableName.codePointAt(offset);
        if (!Character.isUnicodeIdentifierStart(first)) {
            return false;
        }
        offset += Character.charCount(first);

        while (offset < variableName.length()) {
            int codePoint = variableName.codePointAt(offset);
            if (!Character.isUnicodeIdentifierPart(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private void validateStringOrExpression(
            JsonNode value,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (value == null) {
            return;
        }
        if (!value.isString()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "STRING_OR_EXPRESSION_REQUIRED",
                    "Field must be a string or JSONata expression producing a string"
            ));
            return;
        }
        jsonataExpressionValidator.validate(
                value.stringValue(),
                location,
                false,
                false,
                issues
        );
    }

    private void validateJsonataValues(
            JsonNode value,
            String location,
            boolean allowResult,
            boolean allowErrorOutput,
            List<AslValidationIssue> issues
    ) {
        if (value == null) {
            return;
        }
        if (value.isString()) {
            jsonataExpressionValidator.validate(
                    value.stringValue(),
                    location,
                    allowResult,
                    allowErrorOutput,
                    issues
            );
            return;
        }
        if (value.isObject()) {
            for (Map.Entry<String, JsonNode> property : value.properties()) {
                if (property.getKey().endsWith(".$")) {
                    issues.add(issue(
                            location + "." + property.getKey(),
                            AslValidationCategory.DIALECT,
                            "JSONPATH_FIELD_NOT_ALLOWED",
                            "Keys ending in .$ are not allowed in the scheduler JSONata dialect"
                    ));
                }
                validateJsonataValues(
                        property.getValue(),
                        location + "." + property.getKey(),
                        allowResult,
                        allowErrorOutput,
                        issues
                );
            }
            return;
        }
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                validateJsonataValues(
                        value.get(index),
                        location + "[" + index + "]",
                        allowResult,
                        allowErrorOutput,
                        issues
                );
            }
        }
    }

    private AslValidationIssue issue(
            String location,
            AslValidationCategory category,
            String code,
            String message
    ) {
        return new AslValidationIssue(location, category, code, message);
    }
}
