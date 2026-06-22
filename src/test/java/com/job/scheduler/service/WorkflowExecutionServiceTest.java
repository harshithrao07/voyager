package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.enums.WorkflowPriority;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import com.job.scheduler.workflow.asl.runtime.AslDefinitionNavigator;
import com.job.scheduler.workflow.asl.runtime.WorkflowPayloadLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceTest {
    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private ExecutionScopeRepository executionScopeRepository;

    private ObjectMapper objectMapper;
    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new WorkflowExecutionService(
                workflowRepository,
                workflowExecutionRepository,
                executionScopeRepository,
                new AslDefinitionNavigator(objectMapper),
                objectMapper,
                WorkflowPayloadLimits.defaults(objectMapper)
        );
    }

    @Test
    void createsExecutionPinnedToDefinitionAndInitializesRootScope() {
        Workflow workflow = workflow();
        WorkflowDefinition definition = definition(workflow);
        workflow.setActiveDefinition(definition);
        UUID executionId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        Instant scheduledFor = Instant.parse("2026-06-21T06:00:00Z");

        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowExecutionRepository.findByWorkflowAndScheduledFor(
                workflow,
                scheduledFor
        )).thenReturn(Optional.empty());
        when(workflowExecutionRepository
                .findFirstByWorkflowOrderByRunNumberDesc(workflow))
                .thenReturn(Optional.empty());
        when(workflowExecutionRepository.save(any(WorkflowExecution.class)))
                .thenAnswer(invocation -> {
                    WorkflowExecution execution = invocation.getArgument(0);
                    execution.setId(executionId);
                    return execution;
                });
        when(executionScopeRepository.save(any(ExecutionScope.class)))
                .thenAnswer(invocation -> {
                    ExecutionScope scope = invocation.getArgument(0);
                    scope.setId(scopeId);
                    return scope;
                });

        var created = service.createExecution(
                workflow.getId(),
                objectMapper.createObjectNode().put("orderId", "100"),
                scheduledFor
        );

        assertThat(created.workflowExecutionId()).isEqualTo(executionId);
        assertThat(created.rootScopeId()).isEqualTo(scopeId);

        ArgumentCaptor<ExecutionScope> scopeCaptor =
                ArgumentCaptor.forClass(ExecutionScope.class);
        verify(executionScopeRepository).save(scopeCaptor.capture());
        ExecutionScope root = scopeCaptor.getValue();
        assertThat(root.getScopeType()).isEqualTo(ExecutionScopeType.ROOT);
        assertThat(root.getScopePath()).isEqualTo("root");
        assertThat(root.getCurrentStateName()).isEqualTo("Prepare");
        assertThat(objectMapper.readTree(root.getCurrentStateInput())
                .get("orderId").stringValue()).isEqualTo("100");
        assertThat(root.getWorkflowExecution().getWorkflowDefinition())
                .isSameAs(definition);
        assertThat(root.getWorkflowExecution().getDeadlineAt()).isNotNull();
    }

    @Test
    void rejectsOversizedWorkflowInputBeforePersistence() {
        Workflow workflow = workflow();
        workflow.setActiveDefinition(definition(workflow));
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        service = new WorkflowExecutionService(
                workflowRepository,
                workflowExecutionRepository,
                executionScopeRepository,
                new AslDefinitionNavigator(objectMapper),
                objectMapper,
                new WorkflowPayloadLimits(
                        objectMapper,
                        16,
                        1024,
                        1024,
                        1024,
                        1024,
                        1024
                )
        );

        assertThatThrownBy(() -> service.createExecution(
                workflow.getId(),
                objectMapper.createObjectNode().put("value", "x".repeat(32)),
                null
        ))
                .isInstanceOf(
                        com.job.scheduler.workflow.asl.runtime
                                .WorkflowPayloadLimitExceededException.class
                );
    }

    @Test
    void allowsManualRunForPausedWorkflow() {
        Workflow workflow = workflow();
        workflow.setStatus(WorkflowStatus.PAUSED);
        workflow.setActiveDefinition(definition(workflow));
        UUID executionId = UUID.randomUUID();

        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowExecutionRepository
                .findFirstByWorkflowOrderByRunNumberDesc(workflow))
                .thenReturn(Optional.empty());
        when(workflowExecutionRepository.save(any(WorkflowExecution.class)))
                .thenAnswer(invocation -> {
                    WorkflowExecution execution = invocation.getArgument(0);
                    execution.setId(executionId);
                    return execution;
                });
        when(executionScopeRepository.save(any(ExecutionScope.class)))
                .thenAnswer(invocation -> {
                    ExecutionScope scope = invocation.getArgument(0);
                    scope.setId(UUID.randomUUID());
                    return scope;
                });

        var created = service.createExecution(
                workflow.getId(),
                objectMapper.createObjectNode(),
                null
        );

        assertThat(created.workflowExecutionId()).isEqualTo(executionId);
    }

    @Test
    void rejectsManualRunForArchivedWorkflow() {
        Workflow workflow = workflow();
        workflow.setStatus(WorkflowStatus.ARCHIVED);
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));

        assertThatThrownBy(() -> service.createExecution(
                workflow.getId(),
                objectMapper.createObjectNode(),
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHIVED");
    }

    @Test
    void rejectsScheduledRunForPausedWorkflow() {
        Workflow workflow = workflow();
        workflow.setStatus(WorkflowStatus.PAUSED);
        workflow.setActiveDefinition(definition(workflow));

        assertThatThrownBy(() -> service.createExecution(
                workflow,
                objectMapper.createObjectNode(),
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAUSED");
    }

    private Workflow workflow() {
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID());
        workflow.setName("Workflow");
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setPriority(WorkflowPriority.MEDIUM);
        workflow.setTimezone("UTC");
        workflow.setIdempotencyKey("execution-service-test");
        return workflow;
    }

    private WorkflowDefinition definition(Workflow workflow) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(UUID.randomUUID());
        definition.setWorkflow(workflow);
        definition.setRevision(1);
        definition.setDefinition("""
                {
                  "StartAt": "Prepare",
                  "TimeoutSeconds": 60,
                  "States": {
                    "Prepare": {
                      "Type": "Pass",
                      "Next": "Done"
                    },
                    "Done": {
                      "Type": "Succeed"
                    }
                  }
                }
                """);
        definition.setDefinitionHash("a".repeat(64));
        return definition;
    }
}
