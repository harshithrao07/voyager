package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import com.job.scheduler.workflow.asl.runtime.CreatedWorkflowExecution;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowSchedulingService {
    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final ExecutionScopeRepository executionScopeRepository;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowScheduleCalculator scheduleCalculator;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<CreatedWorkflowExecution> materializeDueExecutions(
            Instant now,
            int claimLimit
    ) {
        List<Workflow> workflows =
                workflowRepository.claimDueWorkflows(now, claimLimit);
        List<CreatedWorkflowExecution> created =
                new ArrayList<>(workflows.size());

        for (Workflow workflow : workflows) {
            Instant scheduledFor = workflow.getNextRunAt();
            created.add(workflowExecutionService.createExecution(
                    workflow,
                    objectMapper.createObjectNode(),
                    scheduledFor
            ));
            workflow.setNextRunAt(
                    scheduleCalculator.nextOccurrenceAfter(
                            workflow,
                            scheduledFor
                    )
            );
            workflowRepository.save(workflow);
        }
        return List.copyOf(created);
    }

    @Transactional
    public void releaseFailedStartClaim(UUID rootScopeId) {
        ExecutionScope root = executionScopeRepository
                .findByIdForUpdate(rootScopeId)
                .orElse(null);
        if (root == null
                || root.getStatus() != ExecutionScopeStatus.RUNNING
                || root.getWorkflowExecution().getStatus()
                != WorkflowExecutionStatus.PENDING) {
            return;
        }
        root.setStatus(ExecutionScopeStatus.PENDING);
        executionScopeRepository.save(root);
        workflowExecutionRepository.save(root.getWorkflowExecution());
    }
}
