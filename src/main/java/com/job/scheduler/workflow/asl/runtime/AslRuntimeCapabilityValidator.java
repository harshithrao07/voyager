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
            String type = state.path("Type").stringValue();
            String stateLocation = location + "." + entry.getKey();
            if (!SUPPORTED_STATE_TYPES.contains(type)) {
                issues.add(new AslValidationIssue(
                        stateLocation,
                        AslValidationCategory.RUNTIME_SUPPORT,
                        "STATE_RUNTIME_UNSUPPORTED",
                        type + " state execution is not implemented yet"
                ));
            }
        });
    }
}
