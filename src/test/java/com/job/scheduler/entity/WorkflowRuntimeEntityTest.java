package com.job.scheduler.entity;

import com.job.scheduler.enums.AslStateType;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.enums.StateExecutionStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRuntimeEntityTest {

    @Test
    void retryAttemptsRemainUnderOneLogicalStateExecution() {
        WorkflowExecution workflowExecution = new WorkflowExecution();

        ExecutionScope scope = new ExecutionScope();
        scope.setWorkflowExecution(workflowExecution);
        scope.setScopeType(ExecutionScopeType.ROOT);
        scope.setScopePath("root");
        scope.setStatus(ExecutionScopeStatus.RETRY_WAIT);
        scope.setCurrentStateName("ChargeOrder");

        StateExecution stateExecution = new StateExecution();
        stateExecution.setExecutionScope(scope);
        stateExecution.setSequenceNumber(1);
        stateExecution.setStateName("ChargeOrder");
        stateExecution.setStateType(AslStateType.TASK);
        stateExecution.setStatus(StateExecutionStatus.RETRY_WAIT);
        stateExecution.setInput("{}");

        StateExecutionAttempt firstAttempt = new StateExecutionAttempt();
        firstAttempt.setStateExecution(stateExecution);
        firstAttempt.setAttemptNumber(1);
        firstAttempt.setStatus(StateExecutionAttemptStatus.FAILED);

        StateExecutionAttempt secondAttempt = new StateExecutionAttempt();
        secondAttempt.setStateExecution(stateExecution);
        secondAttempt.setAttemptNumber(2);
        secondAttempt.setStatus(StateExecutionAttemptStatus.PENDING);

        assertThat(firstAttempt.getStateExecution()).isSameAs(stateExecution);
        assertThat(secondAttempt.getStateExecution()).isSameAs(stateExecution);
        assertThat(firstAttempt.getAttemptNumber()).isEqualTo(1);
        assertThat(secondAttempt.getAttemptNumber()).isEqualTo(2);
        assertThat(scope.getCurrentStateName()).isEqualTo("ChargeOrder");
    }

    @Test
    void parallelAndMapChildrenUseSeparateScopePaths() {
        WorkflowExecution workflowExecution = new WorkflowExecution();

        ExecutionScope root = scope(
                workflowExecution,
                null,
                ExecutionScopeType.ROOT,
                "root"
        );
        ExecutionScope branch = scope(
                workflowExecution,
                root,
                ExecutionScopeType.PARALLEL_BRANCH,
                "root/state-3/branch-0"
        );
        ExecutionScope mapItem = scope(
                workflowExecution,
                branch,
                ExecutionScopeType.MAP_ITERATION,
                "root/state-3/branch-0/state-2/item-12"
        );

        assertThat(branch.getParentScope()).isSameAs(root);
        assertThat(mapItem.getParentScope()).isSameAs(branch);
        assertThat(mapItem.getScopePath())
                .isEqualTo("root/state-3/branch-0/state-2/item-12");
    }

    private ExecutionScope scope(
            WorkflowExecution workflowExecution,
            ExecutionScope parent,
            ExecutionScopeType type,
            String path
    ) {
        ExecutionScope scope = new ExecutionScope();
        scope.setWorkflowExecution(workflowExecution);
        scope.setParentScope(parent);
        scope.setScopeType(type);
        scope.setScopePath(path);
        return scope;
    }
}
