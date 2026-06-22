package com.job.scheduler.workflow.asl.validation;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AslMachineDefinitionValidator implements AslDefinitionValidator {
    private static final Set<String> ALLOWED_ROOT_FIELDS = Set.of(
            "Comment",
            "TimeoutSeconds",
            "StartAt",
            "States",
            "QueryLanguage"
    );
    private static final int MAX_STATE_NAME_CODE_POINTS = 80;
    private final AslStateDefinitionValidator stateDefinitionValidator;
    private final AslMachineGraphValidator graphValidator;

    public AslMachineDefinitionValidator(
            AslStateDefinitionValidator stateDefinitionValidator,
            AslMachineGraphValidator graphValidator
    ) {
        this.stateDefinitionValidator = stateDefinitionValidator;
        this.graphValidator = graphValidator;
    }

    @Override
    public AslValidationResult validate(JsonNode definition) {
        List<AslValidationIssue> issues = new ArrayList<>();
        validateMachine(definition, "$", false, Set.of(), issues);
        return new AslValidationResult(issues);
    }

    private void validateMachine(
            JsonNode definition,
            String location,
            boolean itemProcessor,
            Set<String> outerVariables,
            List<AslValidationIssue> issues
    ) {
        if (definition == null || !definition.isObject()) {
            issues.add(issue(
                    location,
                    AslValidationCategory.ASL,
                    "MACHINE_NOT_OBJECT",
                    "State machine definition must be a JSON object"
            ));
            return;
        }

        validateRootFields(definition, location, itemProcessor, issues);
        validateComment(definition.get("Comment"), location, issues);
        validateTimeoutSeconds(definition.get("TimeoutSeconds"), location, issues);
        validateQueryLanguage(definition.get("QueryLanguage"), location, issues);
        validateVersion(definition.get("Version"), location, issues);

        JsonNode startAt = definition.get("StartAt");
        String startStateName = validateStartAt(startAt, location, issues);

        JsonNode states = definition.get("States");
        if (!validateStatesContainer(states, location, issues)) {
            return;
        }

        validateStartStateExists(startStateName, states, location, issues);
        Set<String> visibleVariables = validateVariableScope(
                states,
                location,
                outerVariables,
                issues
        );
        validateStateEntries(states, location, visibleVariables, issues);
        graphValidator.validate(startStateName, states, location, issues);
    }

    private void validateRootFields(
            JsonNode definition,
            String location,
            boolean itemProcessor,
            List<AslValidationIssue> issues
    ) {
        for (Map.Entry<String, JsonNode> property : definition.properties()) {
            String fieldName = property.getKey();
            boolean allowedItemProcessorField =
                    itemProcessor && "ProcessorConfig".equals(fieldName);
            if (!ALLOWED_ROOT_FIELDS.contains(fieldName)
                    && !"Version".equals(fieldName)
                    && !allowedItemProcessorField) {
                issues.add(issue(
                        location + "." + fieldName,
                        AslValidationCategory.ASL,
                        "UNKNOWN_MACHINE_FIELD",
                        "Unknown state machine field: " + fieldName
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
                    "COMMENT_NOT_STRING",
                    "Comment must be a string"
            ));
        }
    }

    private void validateTimeoutSeconds(
            JsonNode timeoutSeconds,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (timeoutSeconds == null) {
            return;
        }
        if (!timeoutSeconds.isIntegralNumber() || !timeoutSeconds.canConvertToInt()
                || timeoutSeconds.intValue() <= 0) {
            issues.add(issue(
                    location + ".TimeoutSeconds",
                    AslValidationCategory.ASL,
                    "INVALID_MACHINE_TIMEOUT",
                    "TimeoutSeconds must be a positive integer"
            ));
        }
    }

    private void validateQueryLanguage(
            JsonNode queryLanguage,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (queryLanguage == null) {
            return;
        }
        if (!queryLanguage.isString() || !"JSONata".equals(queryLanguage.stringValue())) {
            issues.add(issue(
                    location + ".QueryLanguage",
                    AslValidationCategory.DIALECT,
                    "UNSUPPORTED_QUERY_LANGUAGE",
                    "QueryLanguage must be JSONata when provided"
            ));
        }
    }

    private void validateVersion(
            JsonNode version,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (version != null) {
            issues.add(issue(
                    location + ".Version",
                    AslValidationCategory.DIALECT,
                    "VERSION_NOT_ALLOWED",
                    "Version is omitted in the scheduler ASL dialect"
            ));
        }
    }

    private String validateStartAt(
            JsonNode startAt,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (startAt == null || !startAt.isString() || startAt.stringValue().isBlank()) {
            issues.add(issue(
                    location + ".StartAt",
                    AslValidationCategory.ASL,
                    "INVALID_START_AT",
                    "StartAt must be a nonblank string"
            ));
            return null;
        }
        return startAt.stringValue();
    }

    private boolean validateStatesContainer(
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (states == null || !states.isObject()) {
            issues.add(issue(
                    location + ".States",
                    AslValidationCategory.ASL,
                    "STATES_NOT_OBJECT",
                    "States must be a JSON object"
            ));
            return false;
        }
        if (states.isEmpty()) {
            issues.add(issue(
                    location + ".States",
                    AslValidationCategory.ASL,
                    "STATES_EMPTY",
                    "States must contain at least one state"
            ));
            return false;
        }
        return true;
    }

    private void validateStartStateExists(
            String startStateName,
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (startStateName != null && !states.has(startStateName)) {
            issues.add(issue(
                    location + ".StartAt",
                    AslValidationCategory.ASL,
                    "START_STATE_NOT_FOUND",
                    "StartAt must name a state in States"
            ));
        }
    }

    private void validateStateEntries(
            JsonNode states,
            String location,
            Set<String> visibleVariables,
            List<AslValidationIssue> issues
    ) {
        for (Map.Entry<String, JsonNode> entry : states.properties()) {
            String stateName = entry.getKey();
            String stateLocation = location + ".States." + stateName;

            if (stateName.isBlank()) {
                issues.add(issue(
                        stateLocation,
                        AslValidationCategory.ASL,
                        "STATE_NAME_BLANK",
                        "State name must not be blank"
                ));
            }
            if (stateName.codePointCount(0, stateName.length()) > MAX_STATE_NAME_CODE_POINTS) {
                issues.add(issue(
                        stateLocation,
                        AslValidationCategory.ASL,
                        "STATE_NAME_TOO_LONG",
                        "State name must not exceed 80 Unicode characters"
                ));
            }

            JsonNode state = entry.getValue();
            if (state == null || !state.isObject()) {
                issues.add(issue(
                        stateLocation,
                        AslValidationCategory.ASL,
                        "STATE_NOT_OBJECT",
                        "State definition must be a JSON object"
                ));
                continue;
            }

            JsonNode type = state.get("Type");
            if (type == null || !type.isString() || type.stringValue().isBlank()) {
                issues.add(issue(
                        stateLocation + ".Type",
                        AslValidationCategory.ASL,
                        "INVALID_STATE_TYPE",
                        "State Type must be a nonblank string"
                ));
                continue;
            }

            stateDefinitionValidator.validate(
                    stateName,
                    state,
                    states,
                    location,
                    (definition, nestedLocation, itemProcessor, nestedIssues) ->
                            validateMachine(
                                    definition,
                                    nestedLocation,
                                    itemProcessor,
                                    visibleVariables,
                                    nestedIssues
                            ),
                    issues
            );
        }
    }

    private Set<String> validateVariableScope(
            JsonNode states,
            String location,
            Set<String> outerVariables,
            List<AslValidationIssue> issues
    ) {
        Map<String, List<String>> assignmentLocations = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : states.properties()) {
            JsonNode state = entry.getValue();
            if (state == null || !state.isObject()) {
                continue;
            }
            String stateLocation = location + ".States." + entry.getKey();
            collectAssignNames(
                    state.get("Assign"),
                    stateLocation + ".Assign",
                    assignmentLocations
            );
            collectChoiceAssignNames(
                    state.get("Choices"),
                    stateLocation + ".Choices",
                    assignmentLocations
            );
            collectCatchAssignNames(
                    state.get("Catch"),
                    stateLocation + ".Catch",
                    assignmentLocations
            );
        }

        for (Map.Entry<String, List<String>> assignment : assignmentLocations.entrySet()) {
            if (!outerVariables.contains(assignment.getKey())) {
                continue;
            }
            for (String assignmentLocation : assignment.getValue()) {
                issues.add(issue(
                        assignmentLocation,
                        AslValidationCategory.ASL,
                        "OUTER_VARIABLE_REASSIGNMENT",
                        "Nested scope cannot assign outer variable: " + assignment.getKey()
                ));
            }
        }

        Set<String> visibleVariables = new HashSet<>(outerVariables);
        visibleVariables.addAll(assignmentLocations.keySet());
        return Set.copyOf(visibleVariables);
    }

    private void collectChoiceAssignNames(
            JsonNode choices,
            String location,
            Map<String, List<String>> assignmentLocations
    ) {
        if (choices == null || !choices.isArray()) {
            return;
        }
        for (int index = 0; index < choices.size(); index++) {
            JsonNode choice = choices.get(index);
            if (choice != null && choice.isObject()) {
                collectAssignNames(
                        choice.get("Assign"),
                        location + "[" + index + "].Assign",
                        assignmentLocations
                );
            }
        }
    }

    private void collectCatchAssignNames(
            JsonNode catchers,
            String location,
            Map<String, List<String>> assignmentLocations
    ) {
        if (catchers == null || !catchers.isArray()) {
            return;
        }
        for (int index = 0; index < catchers.size(); index++) {
            JsonNode catcher = catchers.get(index);
            if (catcher != null && catcher.isObject()) {
                collectAssignNames(
                        catcher.get("Assign"),
                        location + "[" + index + "].Assign",
                        assignmentLocations
                );
            }
        }
    }

    private void collectAssignNames(
            JsonNode assign,
            String location,
            Map<String, List<String>> assignmentLocations
    ) {
        if (assign == null || !assign.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> assignment : assign.properties()) {
            assignmentLocations
                    .computeIfAbsent(assignment.getKey(), ignored -> new ArrayList<>())
                    .add(location + "." + assignment.getKey());
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
