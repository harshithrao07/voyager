package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import com.job.scheduler.workflow.asl.runtime.CreatedWorkflowExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSchedulingServiceTest {
    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private ExecutionScopeRepository executionScopeRepository;
    @Mock
    private WorkflowExecutionService workflowExecutionService;
    @Mock
    private WorkflowScheduleCalculator scheduleCalculator;

    private WorkflowSchedulingService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowSchedulingService(
                workflowRepository,
                workflowExecutionRepository,
                executionScopeRepository,
                workflowExecutionService,
                scheduleCalculator,
                new ObjectMapper()
        );
    }

    @Test
    void materializesDueOccurrenceAndAdvancesSchedule() throws Exception {
        Instant now = Instant.parse("2026-06-21T10:05:00Z");
        Instant scheduledFor = Instant.parse("2026-06-21T10:00:00Z");
        Instant nextRunAt = Instant.parse("2026-06-21T11:00:00Z");
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID());
        workflow.setNextRunAt(scheduledFor);
        workflow.setScheduledInput("{\"name\":\"Scheduled Harsh\"}");
        CreatedWorkflowExecution created = new CreatedWorkflowExecution(
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        when(workflowRepository.claimDueWorkflows(now, 25))
                .thenReturn(List.of(workflow));
        when(workflowExecutionService.createExecution(
                eq(workflow),
                any(JsonNode.class),
                eq(scheduledFor)
        )).thenReturn(created);
        when(scheduleCalculator.nextOccurrenceAfter(workflow, scheduledFor))
                .thenReturn(nextRunAt);

        var result = service.materializeDueExecutions(now, 25);

        assertThat(result).containsExactly(created);
        assertThat(workflow.getNextRunAt()).isEqualTo(nextRunAt);
        verify(workflowRepository).save(workflow);

        ArgumentCaptor<JsonNode> input =
                ArgumentCaptor.forClass(JsonNode.class);
        verify(workflowExecutionService).createExecution(
                eq(workflow),
                input.capture(),
                eq(scheduledFor)
        );
        assertThat(input.getValue().path("name").asText())
                .isEqualTo("Scheduled Harsh");
    }

    @Test
    void releasesFailedStartClaimWhileExecutionIsPending() {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setStatus(WorkflowExecutionStatus.PENDING);
        ExecutionScope root = new ExecutionScope();
        root.setId(UUID.randomUUID());
        root.setStatus(ExecutionScopeStatus.RUNNING);
        root.setWorkflowExecution(execution);
        when(executionScopeRepository.findByIdForUpdate(root.getId()))
                .thenReturn(Optional.of(root));

        service.releaseFailedStartClaim(root.getId());

        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.PENDING);
        verify(executionScopeRepository).save(root);
        verify(workflowExecutionRepository).save(execution);
    }
}
