package com.job.scheduler.service;

import com.job.scheduler.dto.StartWorkflowExecutionRequestDTO;
import com.job.scheduler.dto.WorkflowExecutionResponseDTO;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.workflow.asl.runtime.CreatedWorkflowExecution;
import com.job.scheduler.workflow.asl.runtime.ExecutionScopeCoordinator;
import com.job.scheduler.workflow.asl.runtime.InterpreterOutcome;
import com.job.scheduler.workflow.asl.runtime.WorkflowInterpreter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

@Service
public class WorkflowExecutionRunner {
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowInterpreter workflowInterpreter;
    private final ExecutionScopeCoordinator scopeCoordinator;
    private final ObjectMapper objectMapper;
    private final int maximumInlineTransitions;

    public WorkflowExecutionRunner(
            WorkflowExecutionService workflowExecutionService,
            WorkflowInterpreter workflowInterpreter,
            ExecutionScopeCoordinator scopeCoordinator,
            ObjectMapper objectMapper,
            @Value("${scheduler.workflow.max-inline-transitions:10000}")
            int maximumInlineTransitions
    ) {
        if (maximumInlineTransitions <= 0) {
            throw new IllegalArgumentException(
                    "Maximum inline workflow transitions must be positive"
            );
        }
        this.workflowExecutionService = workflowExecutionService;
        this.workflowInterpreter = workflowInterpreter;
        this.scopeCoordinator = scopeCoordinator;
        this.objectMapper = objectMapper;
        this.maximumInlineTransitions = maximumInlineTransitions;
    }

    public WorkflowExecutionResponseDTO start(
            UUID workflowId,
            StartWorkflowExecutionRequestDTO request
    ) {
        JsonNode input = request == null || request.input() == null
                ? objectMapper.createObjectNode()
                : request.input();
        CreatedWorkflowExecution created =
                workflowExecutionService.createExecution(
                        workflowId,
                        input,
                        null
                );

        return drive(created);
    }

    public WorkflowExecutionResponseDTO resume(
            UUID workflowExecutionId,
            UUID scopeId
    ) {
        return drive(new CreatedWorkflowExecution(
                workflowExecutionId,
                scopeId
        ));
    }

    /**
     * Drives a frontier of runnable scopes. A scope that suspends (Task
     * dispatch, timed wait, joining on children) drops out of the frontier; a
     * Parallel fork pushes its branch scopes; a settled child scope resumes its
     * parent compound scope. Re-entrant: when a worker or the wait scheduler
     * resumes a single scope, the same loop carries any resulting parent join
     * through to completion.
     */
    private WorkflowExecutionResponseDTO drive(CreatedWorkflowExecution created) {
        UUID entryScopeId = created.rootScopeId();
        Deque<UUID> runnable = new ArrayDeque<>();
        runnable.add(entryScopeId);
        InterpreterOutcome entryOutcome = null;
        int transitions = 0;

        while (!runnable.isEmpty()) {
            if (transitions++ >= maximumInlineTransitions) {
                throw new IllegalStateException(
                        "Workflow exceeded the maximum inline transition limit"
                );
            }
            UUID scopeId = runnable.poll();
            InterpreterOutcome outcome = workflowInterpreter.advance(scopeId);
            if (scopeId.equals(entryScopeId)) {
                entryOutcome = outcome;
            }
            scheduleFollowups(scopeId, outcome, runnable);
        }

        return toResponse(created.workflowExecutionId(), entryOutcome);
    }

    private void scheduleFollowups(
            UUID scopeId,
            InterpreterOutcome outcome,
            Deque<UUID> runnable
    ) {
        if (outcome instanceof InterpreterOutcome.Continued) {
            runnable.add(scopeId);
        } else if (outcome instanceof InterpreterOutcome.Waiting waiting) {
            if (!waiting.wakeAt().isAfter(Instant.now())) {
                runnable.add(scopeId);
            }
        } else if (outcome instanceof InterpreterOutcome.Forked forked) {
            runnable.addAll(forked.childScopeIds());
        } else if (outcome instanceof InterpreterOutcome.Succeeded
                || outcome instanceof InterpreterOutcome.Failed) {
            scopeCoordinator.onChildSettled(scopeId).ifPresent(runnable::add);
        }
        // Joining, Dispatched, RetryScheduled: the scope is suspended; the
        // worker, wait scheduler, or a settling child will resume progress.
    }

    private WorkflowExecutionResponseDTO toResponse(
            UUID workflowExecutionId,
            InterpreterOutcome outcome
    ) {
        if (outcome instanceof InterpreterOutcome.Succeeded succeeded) {
            return new WorkflowExecutionResponseDTO(
                    workflowExecutionId,
                    WorkflowExecutionStatus.SUCCEEDED,
                    succeeded.output(),
                    null,
                    null,
                    null,
                    null
            );
        }
        if (outcome instanceof InterpreterOutcome.Failed failed) {
            return new WorkflowExecutionResponseDTO(
                    workflowExecutionId,
                    WorkflowExecutionStatus.FAILED,
                    null,
                    failed.error(),
                    failed.cause(),
                    null,
                    null
            );
        }
        if (outcome instanceof InterpreterOutcome.Waiting waiting) {
            return new WorkflowExecutionResponseDTO(
                    workflowExecutionId,
                    WorkflowExecutionStatus.WAITING,
                    null,
                    null,
                    null,
                    waiting.wakeAt(),
                    null
            );
        }
        if (outcome instanceof InterpreterOutcome.Dispatched dispatched) {
            return new WorkflowExecutionResponseDTO(
                    workflowExecutionId,
                    WorkflowExecutionStatus.QUEUED,
                    null,
                    null,
                    null,
                    null,
                    dispatched.stateExecutionAttemptId()
            );
        }
        if (outcome instanceof InterpreterOutcome.RetryScheduled retry) {
            return new WorkflowExecutionResponseDTO(
                    workflowExecutionId,
                    WorkflowExecutionStatus.WAITING,
                    null,
                    null,
                    null,
                    retry.availableAt(),
                    retry.stateExecutionAttemptId()
            );
        }
        // Forked or Joining: branches are in flight; the execution is running.
        return new WorkflowExecutionResponseDTO(
                workflowExecutionId,
                WorkflowExecutionStatus.RUNNING,
                null,
                null,
                null,
                null,
                null
        );
    }
}
