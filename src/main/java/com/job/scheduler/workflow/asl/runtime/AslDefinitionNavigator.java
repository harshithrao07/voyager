package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.entity.ExecutionScope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AslDefinitionNavigator {
    private final ObjectMapper objectMapper;

    public AslDefinitionNavigator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode readMachine(ExecutionScope scope) {
        return switch (scope.getScopeType()) {
            case ROOT -> readRootMachine(scope);
            case PARALLEL_BRANCH -> readBranchMachine(scope);
            case MAP_ITERATION -> readIterationMachine(scope);
        };
    }

    private JsonNode readRootMachine(ExecutionScope scope) {
        try {
            return objectMapper.readTree(
                    scope.getWorkflowExecution()
                            .getWorkflowDefinition()
                            .getDefinition()
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read persisted ASL definition",
                    exception
            );
        }
    }

    /**
     * Resolves the nested machine owned by a Parallel branch scope by walking up
     * to the owning Parallel state in the parent machine and selecting the branch
     * recorded on the scope. Relies only on durable scope identity
     * (parent, owner state name, branch index), so it resolves deterministically
     * after a restart.
     */
    private JsonNode readBranchMachine(ExecutionScope scope) {
        JsonNode owner = state(requireParent(scope), requireOwnerStateName(scope));
        JsonNode branches = owner.get("Branches");
        if (branches == null || !branches.isArray()) {
            throw new IllegalStateException(
                    "Parallel state " + scope.getOwnerStateName()
                            + " has no Branches array"
            );
        }
        Integer index = scope.getBranchIndex();
        if (index == null || index < 0 || index >= branches.size()) {
            throw new IllegalStateException(
                    "Parallel branch index is out of range: " + index
            );
        }
        JsonNode branch = branches.get(index);
        if (branch == null || !branch.isObject()) {
            throw new IllegalStateException(
                    "Parallel branch " + index + " is not a machine object"
            );
        }
        return branch;
    }

    /**
     * Resolves the nested machine owned by a Map iteration scope by walking up to
     * the owning Map state in the parent machine and selecting its ItemProcessor.
     * Every iteration of one Map state shares the same ItemProcessor machine; the
     * iteration identity lives on the scope (item index), not in the definition.
     */
    private JsonNode readIterationMachine(ExecutionScope scope) {
        JsonNode owner = state(requireParent(scope), requireOwnerStateName(scope));
        JsonNode processor = owner.get("ItemProcessor");
        if (processor == null || !processor.isObject()) {
            throw new IllegalStateException(
                    "Map state " + scope.getOwnerStateName()
                            + " has no ItemProcessor object"
            );
        }
        return processor;
    }

    private ExecutionScope requireParent(ExecutionScope scope) {
        ExecutionScope parent = scope.getParentScope();
        if (parent == null) {
            throw new IllegalStateException(
                    "Nested execution scope " + scope.getScopePath()
                            + " has no parent scope"
            );
        }
        return parent;
    }

    private String requireOwnerStateName(ExecutionScope scope) {
        String ownerStateName = scope.getOwnerStateName();
        if (ownerStateName == null) {
            throw new IllegalStateException(
                    "Nested execution scope " + scope.getScopePath()
                            + " has no owner state name"
            );
        }
        return ownerStateName;
    }

    public JsonNode state(ExecutionScope scope, String stateName) {
        JsonNode state = readMachine(scope).path("States").get(stateName);
        if (state == null || !state.isObject()) {
            throw new IllegalStateException(
                    "Current ASL state does not exist: " + stateName
            );
        }
        return state;
    }

    public String startAt(ExecutionScope scope) {
        JsonNode startAt = readMachine(scope).get("StartAt");
        if (startAt == null || !startAt.isString()) {
            throw new IllegalStateException("ASL definition has no valid StartAt");
        }
        return startAt.stringValue();
    }
}
