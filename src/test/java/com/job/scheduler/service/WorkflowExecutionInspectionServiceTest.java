package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.AslStateType;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.enums.StateExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionTrigger;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionInspectionServiceTest {
    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private ExecutionScopeRepository executionScopeRepository;
    @Mock
    private StateExecutionRepository stateExecutionRepository;
    @Mock
    private StateExecutionAttemptRepository attemptRepository;

    private WorkflowExecutionInspectionService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowExecutionInspectionService(
                workflowRepository,
                workflowExecutionRepository,
                executionScopeRepository,
                stateExecutionRepository,
                attemptRepository,
                new ObjectMapper()
        );
    }

    @Test
    void listsExecutionSummariesWithBoundedPagination() {
        WorkflowExecution execution = execution();
        when(workflowRepository.findById(execution.getWorkflow().getId()))
                .thenReturn(Optional.of(execution.getWorkflow()));
        when(workflowExecutionRepository
                .findFiltered(
                        any(Workflow.class),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(false),
                        eq(null),
                        eq(null),
                        any(Pageable.class)
                )).thenReturn(new PageImpl<>(List.of(execution)));

        var page = service.listExecutions(
                execution.getWorkflow().getId(),
                -1,
                1000,
                null,
                null,
                null,
                null
        );

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).runNumber()).isEqualTo(4);
        assertThat(page.content().get(0).input().get("orderId").stringValue())
                .isEqualTo("100");
    }

    @Test
    void passesStatusRevisionTriggerAndRunSearchToRepository() {
        WorkflowExecution execution = execution();
        when(workflowRepository.findById(execution.getWorkflow().getId()))
                .thenReturn(Optional.of(execution.getWorkflow()));
        when(workflowExecutionRepository.findFiltered(
                any(Workflow.class),
                eq(WorkflowExecutionStatus.SUCCEEDED),
                eq(2L),
                eq(true),
                eq(true),
                eq(null),
                eq(4L),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(execution)));

        var page = service.listExecutions(
                execution.getWorkflow().getId(),
                0,
                20,
                WorkflowExecutionStatus.SUCCEEDED,
                2L,
                WorkflowExecutionTrigger.SCHEDULED,
                " 4 "
        );

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).id()).isEqualTo(execution.getId());
    }

    @Test
    void invalidExactSearchCannotFallBackToUnfilteredResults() {
        WorkflowExecution execution = execution();
        when(workflowRepository.findById(execution.getWorkflow().getId()))
                .thenReturn(Optional.of(execution.getWorkflow()));
        when(workflowExecutionRepository.findFiltered(
                any(Workflow.class),
                eq(null),
                eq(null),
                eq(false),
                eq(true),
                eq(null),
                eq(null),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        var page = service.listExecutions(
                execution.getWorkflow().getId(),
                0,
                20,
                null,
                null,
                WorkflowExecutionTrigger.MANUAL,
                "not-an-id-or-run"
        );

        assertThat(page.content()).isEmpty();
    }

    @Test
    void returnsScopesStateVisitsAndAttemptsInExecutionDetail() {
        WorkflowExecution execution = execution();
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(execution);
        scope.setScopeType(ExecutionScopeType.ROOT);
        scope.setScopePath("root");
        scope.setStatus(ExecutionScopeStatus.WAITING);
        scope.setVariables("{\"customer\":\"Ada\"}");

        StateExecution state = new StateExecution();
        state.setId(UUID.randomUUID());
        state.setExecutionScope(scope);
        state.setSequenceNumber(2);
        state.setStateName("Call");
        state.setStateType(AslStateType.TASK);
        state.setStatus(StateExecutionStatus.RETRY_WAIT);
        state.setInput("{\"orderId\":\"100\"}");

        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setStateExecution(state);
        attempt.setAttemptNumber(1);
        attempt.setStatus(StateExecutionAttemptStatus.FAILED);
        attempt.setArguments("{\"orderId\":\"100\"}");
        attempt.setError("Temporary");

        when(workflowExecutionRepository.findById(execution.getId()))
                .thenReturn(Optional.of(execution));
        when(executionScopeRepository
                .findByWorkflowExecutionOrderByScopePathAsc(execution))
                .thenReturn(List.of(scope));
        when(stateExecutionRepository
                .findByExecutionScopeOrderBySequenceNumberAsc(scope))
                .thenReturn(List.of(state));
        when(attemptRepository
                .findByStateExecutionOrderByAttemptNumberAsc(state))
                .thenReturn(List.of(attempt));

        var detail = service.getExecution(
                execution.getWorkflow().getId(),
                execution.getId()
        );

        assertThat(detail.scopes()).hasSize(1);
        assertThat(detail.scopes().get(0).variables()
                .get("customer").stringValue()).isEqualTo("Ada");
        assertThat(detail.scopes().get(0).stateExecutions().get(0)
                .sequenceNumber()).isEqualTo(2);
        assertThat(detail.scopes().get(0).stateExecutions().get(0)
                .attempts().get(0).error()).isEqualTo("Temporary");
    }

    private WorkflowExecution execution() {
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID());
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(UUID.randomUUID());
        definition.setWorkflow(workflow);
        definition.setRevision(2);

        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID());
        execution.setWorkflow(workflow);
        execution.setWorkflowDefinition(definition);
        execution.setRunNumber(4);
        execution.setStatus(WorkflowExecutionStatus.RUNNING);
        execution.setInput("{\"orderId\":\"100\"}");
        return execution;
    }
}
