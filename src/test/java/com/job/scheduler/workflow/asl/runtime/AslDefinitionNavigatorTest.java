package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AslDefinitionNavigatorTest {
    private static final String DEFINITION = """
            {
              "StartAt": "Fork",
              "States": {
                "Fork": {
                  "Type": "Parallel",
                  "Branches": [
                    {"StartAt": "A", "States": {"A": {"Type": "Succeed"}}},
                    {"StartAt": "B", "States": {"B": {"Type": "Succeed"}}}
                  ],
                  "Next": "Loop"
                },
                "Loop": {
                  "Type": "Map",
                  "ItemProcessor": {
                    "StartAt": "P",
                    "States": {"P": {"Type": "Succeed"}}
                  },
                  "End": true
                }
              }
            }
            """;

    private ObjectMapper objectMapper;
    private AslDefinitionNavigator navigator;
    private ExecutionScope root;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        navigator = new AslDefinitionNavigator(objectMapper);
        root = rootScope(DEFINITION);
    }

    @Test
    void resolvesRootMachineStartAtAndStates() {
        assertThat(navigator.startAt(root)).isEqualTo("Fork");
        assertThat(navigator.state(root, "Loop").get("Type").stringValue())
                .isEqualTo("Map");
    }

    @Test
    void resolvesParallelBranchMachineByBranchIndex() {
        ExecutionScope branch = branchScope(root, "Fork", 1);

        assertThat(navigator.startAt(branch)).isEqualTo("B");
        assertThat(navigator.state(branch, "B").get("Type").stringValue())
                .isEqualTo("Succeed");
    }

    @Test
    void resolvesMapIterationMachineFromItemProcessor() {
        ExecutionScope iteration = iterationScope(root, "Loop", 7);

        assertThat(navigator.startAt(iteration)).isEqualTo("P");
        assertThat(navigator.state(iteration, "P").get("Type").stringValue())
                .isEqualTo("Succeed");
    }

    @Test
    void resolvesNestedMachineThroughTheParentChain() {
        ExecutionScope branch = branchScope(root, "Fork", 0);
        ExecutionScope nestedIteration = iterationScope(branch, "A", 0);

        assertThatThrownBy(() -> navigator.startAt(nestedIteration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ItemProcessor");
    }

    @Test
    void rejectsBranchIndexOutOfRange() {
        ExecutionScope branch = branchScope(root, "Fork", 5);

        assertThatThrownBy(() -> navigator.readMachine(branch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void rejectsNestedScopeWithoutParent() {
        ExecutionScope orphan = new ExecutionScope();
        orphan.setScopeType(ExecutionScopeType.PARALLEL_BRANCH);
        orphan.setScopePath("root/Fork/branch-0");
        orphan.setOwnerStateName("Fork");
        orphan.setBranchIndex(0);

        assertThatThrownBy(() -> navigator.readMachine(orphan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no parent scope");
    }

    private ExecutionScope rootScope(String definition) {
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID());

        WorkflowDefinition workflowDefinition = new WorkflowDefinition();
        workflowDefinition.setId(UUID.randomUUID());
        workflowDefinition.setWorkflow(workflow);
        workflowDefinition.setRevision(1);
        workflowDefinition.setDefinition(definition);

        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID());
        execution.setWorkflow(workflow);
        execution.setWorkflowDefinition(workflowDefinition);

        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(execution);
        scope.setScopeType(ExecutionScopeType.ROOT);
        scope.setScopePath("root");
        return scope;
    }

    private ExecutionScope branchScope(
            ExecutionScope parent,
            String ownerStateName,
            int branchIndex
    ) {
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(parent.getWorkflowExecution());
        scope.setParentScope(parent);
        scope.setScopeType(ExecutionScopeType.PARALLEL_BRANCH);
        scope.setOwnerStateName(ownerStateName);
        scope.setBranchIndex(branchIndex);
        scope.setScopePath(
                parent.getScopePath() + "/" + ownerStateName + "/branch-" + branchIndex
        );
        return scope;
    }

    private ExecutionScope iterationScope(
            ExecutionScope parent,
            String ownerStateName,
            long itemIndex
    ) {
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(parent.getWorkflowExecution());
        scope.setParentScope(parent);
        scope.setScopeType(ExecutionScopeType.MAP_ITERATION);
        scope.setOwnerStateName(ownerStateName);
        scope.setItemIndex(itemIndex);
        scope.setScopePath(
                parent.getScopePath() + "/" + ownerStateName + "/item-" + itemIndex
        );
        return scope;
    }
}
