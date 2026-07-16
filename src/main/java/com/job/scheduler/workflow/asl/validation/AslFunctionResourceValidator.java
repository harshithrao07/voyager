package com.job.scheduler.workflow.asl.validation;

import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.FunctionVersion;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.FunctionVersionStatus;
import com.job.scheduler.repository.FunctionDefinitionRepository;
import com.job.scheduler.repository.FunctionVersionRepository;
import com.job.scheduler.workflow.task.FunctionTaskResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Save-time checks for {@code voyager://function/...} Task resources: the
 * referenced function must exist and be enabled, and the pinned version (or
 * the function's active version when unpinned) must exist and be published.
 * This mirrors the runtime gate in {@code FunctionInvocationService}, so
 * authoring mistakes fail at save instead of as {@code Function.NotFound} on
 * the first run; the runtime gate still owns drift after save (e.g. a function
 * archived later). Recurses into Parallel branches and Map item processors so
 * nested function tasks are covered. Non-function resources are ignored.
 */
@Component
@RequiredArgsConstructor
public class AslFunctionResourceValidator {
    private final FunctionDefinitionRepository functionDefinitionRepository;
    private final FunctionVersionRepository functionVersionRepository;

    public List<AslValidationIssue> validate(JsonNode definition) {
        List<AslValidationIssue> issues = new ArrayList<>();
        validateStates(definition.path("States"), "$.States", issues);
        return List.copyOf(issues);
    }

    private void validateStates(
            JsonNode states,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (!states.isObject()) {
            return;
        }
        states.properties().forEach(entry -> {
            JsonNode state = entry.getValue();
            String stateLocation = location + "." + entry.getKey();
            if ("Task".equals(typeOf(state))) {
                validateResource(state.path("Resource"), stateLocation, issues);
            }
            JsonNode branches = state.path("Branches");
            if (branches.isArray()) {
                for (int index = 0; index < branches.size(); index++) {
                    validateStates(
                            branches.get(index).path("States"),
                            stateLocation + ".Branches[" + index + "].States",
                            issues
                    );
                }
            }
            JsonNode itemProcessor = state.path("ItemProcessor");
            if (itemProcessor.isObject()) {
                validateStates(
                        itemProcessor.path("States"),
                        stateLocation + ".ItemProcessor.States",
                        issues
                );
            }
        });
    }

    private void validateResource(
            JsonNode resourceNode,
            String location,
            List<AslValidationIssue> issues
    ) {
        if (!resourceNode.isString()) {
            return;
        }
        FunctionTaskResource.FunctionResourceRef ref;
        try {
            ref = FunctionTaskResource.parseFunctionResource(resourceNode.stringValue());
        } catch (IllegalArgumentException exception) {
            issues.add(new AslValidationIssue(
                    location,
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "FUNCTION_RESOURCE_INVALID",
                    exception.getMessage()
            ));
            return;
        }
        if (ref == null) {
            return;
        }

        FunctionDefinition function = functionDefinitionRepository
                .findByName(ref.name())
                .orElse(null);
        if (function == null) {
            issues.add(new AslValidationIssue(
                    location,
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "FUNCTION_NOT_FOUND",
                    "Function is not registered: " + ref.name()
            ));
            return;
        }
        if (function.getStatus() != FunctionStatus.ENABLED) {
            issues.add(new AslValidationIssue(
                    location,
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "FUNCTION_DISABLED",
                    "Function " + ref.name() + " is " + function.getStatus()
                            + " and cannot be invoked"
            ));
            return;
        }

        Integer effectiveVersion = ref.version() != null
                ? ref.version()
                : function.getActiveVersion();
        if (effectiveVersion == null) {
            issues.add(new AslValidationIssue(
                    location,
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "FUNCTION_NO_ACTIVE_VERSION",
                    "Function " + ref.name()
                            + " has no active version (publish a version first)"
            ));
            return;
        }

        FunctionVersion version = functionVersionRepository
                .findByFunctionDefinitionAndVersion(function, effectiveVersion)
                .orElse(null);
        if (version == null) {
            issues.add(new AslValidationIssue(
                    location,
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "FUNCTION_VERSION_NOT_FOUND",
                    "Function version does not exist: " + ref.name()
                            + "@v" + effectiveVersion
            ));
            return;
        }
        if (version.getStatus() != FunctionVersionStatus.AVAILABLE) {
            issues.add(new AslValidationIssue(
                    location,
                    AslValidationCategory.RUNTIME_SUPPORT,
                    "FUNCTION_VERSION_NOT_AVAILABLE",
                    "Function version " + ref.name() + "@v" + effectiveVersion
                            + " is " + version.getStatus() + " (publish it first)"
            ));
        }
    }

    private String typeOf(JsonNode state) {
        JsonNode type = state.path("Type");
        return type.isString() ? type.stringValue() : null;
    }
}
