package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import com.job.scheduler.workflow.asl.runtime.AslDefinitionNavigator;
import com.job.scheduler.workflow.asl.runtime.CreatedWorkflowExecution;
import com.job.scheduler.workflow.asl.runtime.WorkflowPayloadLimits;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkflowExecutionService {
    /**
     * Statuses from which an operator may start an ad-hoc execution. PAUSED is
     * allowed because pausing only suspends the cron schedule, not manual runs;
     * a PAUSED workflow still retains its active definition. DRAFT and ARCHIVED
     * remain blocked.
     */
    private static final Set<WorkflowStatus> MANUAL_RUNNABLE_STATUSES =
            Set.of(WorkflowStatus.ACTIVE, WorkflowStatus.PAUSED);

    /**
     * Statuses the scheduler may materialize. Only ACTIVE workflows carry a
     * nextRunAt, so the scheduler should never reach a non-ACTIVE workflow;
     * this gate enforces that invariant even if a claim races a status change.
     */
    private static final Set<WorkflowStatus> SCHEDULED_RUNNABLE_STATUSES =
            Set.of(WorkflowStatus.ACTIVE);

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final ExecutionScopeRepository executionScopeRepository;
    private final AslDefinitionNavigator definitionNavigator;
    private final ObjectMapper objectMapper;
    private final WorkflowPayloadLimits payloadLimits;

    public WorkflowExecutionService(
            WorkflowRepository workflowRepository,
            WorkflowExecutionRepository workflowExecutionRepository,
            ExecutionScopeRepository executionScopeRepository,
            AslDefinitionNavigator definitionNavigator,
            ObjectMapper objectMapper,
            WorkflowPayloadLimits payloadLimits
    ) {
        this.workflowRepository = workflowRepository;
        this.workflowExecutionRepository = workflowExecutionRepository;
        this.executionScopeRepository = executionScopeRepository;
        this.definitionNavigator = definitionNavigator;
        this.objectMapper = objectMapper;
        this.payloadLimits = payloadLimits;
    }

    /**
     * Manual, operator-triggered run. Permits ACTIVE and PAUSED workflows; see
     * {@link #MANUAL_RUNNABLE_STATUSES}.
     */
    @Transactional
    public CreatedWorkflowExecution createExecution(
            UUID workflowId,
            JsonNode input,
            Instant scheduledFor
    ) {
        Workflow workflow = workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow does not exist"
                ));
        return createExecution(
                workflow,
                input,
                scheduledFor,
                MANUAL_RUNNABLE_STATUSES
        );
    }

    /**
     * Scheduled run materialized by the cron scheduler. Permits ACTIVE only;
     * see {@link #SCHEDULED_RUNNABLE_STATUSES}.
     */
    public CreatedWorkflowExecution createExecution(
            Workflow workflow,
            JsonNode input,
            Instant scheduledFor
    ) {
        return createExecution(
                workflow,
                input,
                scheduledFor,
                SCHEDULED_RUNNABLE_STATUSES
        );
    }

    private CreatedWorkflowExecution createExecution(
            Workflow workflow,
            JsonNode input,
            Instant scheduledFor,
            Set<WorkflowStatus> allowedStatuses
    ) {
        if (!allowedStatuses.contains(workflow.getStatus())) {
            throw new IllegalStateException(
                    "Workflow status " + workflow.getStatus()
                            + " cannot create executions; allowed: "
                            + allowedStatuses
            );
        }

        WorkflowDefinition definition = workflow.getActiveDefinition();
        if (definition == null) {
            throw new IllegalStateException(
                    "Workflow has no active definition"
            );
        }

        if (scheduledFor != null) {
            var existing = workflowExecutionRepository
                    .findByWorkflowAndScheduledFor(workflow, scheduledFor);
            if (existing.isPresent()) {
                ExecutionScope root = executionScopeRepository
                        .findByWorkflowExecutionAndScopeType(
                                existing.get(),
                                ExecutionScopeType.ROOT
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "Workflow execution has no root scope"
                        ));
                return new CreatedWorkflowExecution(
                        existing.get().getId(),
                        root.getId()
                );
            }
        }

        long runNumber = workflowExecutionRepository
                .findFirstByWorkflowOrderByRunNumberDesc(workflow)
                .map(latest -> latest.getRunNumber() + 1)
                .orElse(1L);
        Instant now = Instant.now();

        WorkflowExecution execution = new WorkflowExecution();
        execution.setWorkflow(workflow);
        execution.setWorkflowDefinition(definition);
        execution.setRunNumber(runNumber);
        execution.setStatus(WorkflowExecutionStatus.PENDING);
        execution.setScheduledFor(scheduledFor);
        execution.setInput(payloadLimits.serialize(
                input == null ? objectMapper.createObjectNode() : input,
                WorkflowPayloadLimits.Kind.INPUT
        ));
        execution.setDeadlineAt(deadlineAt(definition, now));
        workflowExecutionRepository.save(execution);

        ExecutionScope root = new ExecutionScope();
        root.setWorkflowExecution(execution);
        root.setScopeType(ExecutionScopeType.ROOT);
        root.setScopePath("root");
        root.setStatus(ExecutionScopeStatus.PENDING);
        root.setCurrentStateInput(execution.getInput());
        root.setVariables("{}");
        root.setCurrentStateName(definitionNavigator.startAt(root));
        executionScopeRepository.save(root);

        return new CreatedWorkflowExecution(execution.getId(), root.getId());
    }

    private Instant deadlineAt(
            WorkflowDefinition definition,
            Instant startedAt
    ) {
        try {
            JsonNode machine = objectMapper.readTree(definition.getDefinition());
            JsonNode timeout = machine.get("TimeoutSeconds");
            return timeout == null
                    ? null
                    : startedAt.plusSeconds(timeout.longValue());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read persisted ASL definition",
                    exception
            );
        }
    }

}
