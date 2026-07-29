package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.workflow.asl.validation.AslValidationCategory;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class AslRuntimeCapabilityValidator {
    private static final Set<String> SUPPORTED_STATE_TYPES = Set.of(
            "Pass",
            "Task",
            "Choice",
            "Wait",
            "Succeed",
            "Fail",
            "Parallel",
            "Map"
    );
    private static final Set<String> SUPPORTED_SYSTEM_TASK_RESOURCES = Set.of(
            "voyager://system/webhook",
            "voyager://system/send-email"
    );

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
            JsonNode typeNode = state.get("Type");
            String type = typeNode != null && typeNode.isTextual()
                    ? typeNode.textValue()
                    : "";
            String stateLocation = location + "." + entry.getKey();
            if (!SUPPORTED_STATE_TYPES.contains(type)) {
                issues.add(new AslValidationIssue(
                        stateLocation,
                        AslValidationCategory.RUNTIME_SUPPORT,
                        "STATE_RUNTIME_UNSUPPORTED",
                        type + " state execution is not implemented yet"
                ));
            }
            if ("Task".equals(type)) {
                validateTaskResource(state.get("Resource"), stateLocation, issues);
            }
            JsonNode branches = state.get("Branches");
            if (branches != null && branches.isArray()) {
                for (int index = 0; index < branches.size(); index++) {
                    validateStates(
                            branches.get(index).path("States"),
                            stateLocation + ".Branches[" + index + "].States",
                            issues
                    );
                }
            }
            JsonNode itemProcessor = state.get("ItemProcessor");
            if (itemProcessor != null && itemProcessor.isObject()) {
                validateStates(
                        itemProcessor.path("States"),
                        stateLocation + ".ItemProcessor.States",
                        issues
                );
            }
        });
    }

    private void validateTaskResource(
            JsonNode resourceNode,
            String stateLocation,
            List<AslValidationIssue> issues
    ) {
        if (resourceNode == null || !resourceNode.isTextual()) {
            return;
        }
        String resource = resourceNode.textValue();
        if (SUPPORTED_SYSTEM_TASK_RESOURCES.contains(resource)
                || resource.startsWith("voyager://function/")
                || resource.startsWith("voyager://mcp/")) {
            return;
        }
        issues.add(new AslValidationIssue(
                stateLocation + ".Resource",
                AslValidationCategory.RUNTIME_SUPPORT,
                "TASK_RESOURCE_UNSUPPORTED",
                "Task Resource is not registered or supported: " + resource
        ));
    }
}
