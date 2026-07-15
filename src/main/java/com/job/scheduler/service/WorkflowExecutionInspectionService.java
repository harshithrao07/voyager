package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowExecutionDetailDTO;
import com.job.scheduler.dto.WorkflowExecutionPageDTO;
import com.job.scheduler.dto.WorkflowExecutionScopeDTO;
import com.job.scheduler.dto.WorkflowExecutionSummaryDTO;
import com.job.scheduler.dto.WorkflowStateExecutionAttemptDTO;
import com.job.scheduler.dto.WorkflowStateExecutionDTO;
import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionTrigger;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowExecutionInspectionService {
    private static final int MAX_PAGE_SIZE = 100;

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final ExecutionScopeRepository executionScopeRepository;
    private final StateExecutionRepository stateExecutionRepository;
    private final StateExecutionAttemptRepository attemptRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public WorkflowExecutionPageDTO listExecutions(
            UUID workflowId,
            int page,
            int size,
            WorkflowExecutionStatus status,
            Long revision,
            WorkflowExecutionTrigger trigger,
            String search
    ) {
        Workflow workflow = findWorkflow(workflowId);
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        ExecutionSearch executionSearch = parseSearch(search);
        Page<WorkflowExecution> executions =
                workflowExecutionRepository.findFiltered(
                        workflow,
                        status,
                        revision,
                        trigger == null
                                ? null
                                : trigger == WorkflowExecutionTrigger.SCHEDULED,
                        executionSearch.provided(),
                        executionSearch.executionId(),
                        executionSearch.runNumber(),
                        PageRequest.of(normalizedPage, normalizedSize)
                );
        return new WorkflowExecutionPageDTO(
                executions.getContent().stream()
                        .map(this::toSummary)
                        .toList(),
                executions.getNumber(),
                executions.getSize(),
                executions.getTotalElements(),
                executions.getTotalPages(),
                executions.isFirst(),
                executions.isLast()
        );
    }

    private ExecutionSearch parseSearch(String search) {
        if (search == null || search.isBlank()) {
            return new ExecutionSearch(false, null, null);
        }
        String normalized = search.trim();
        try {
            return new ExecutionSearch(
                    true,
                    UUID.fromString(normalized),
                    null
            );
        } catch (IllegalArgumentException ignored) {
            // A search term can target either an execution UUID or run number.
        }
        try {
            return new ExecutionSearch(
                    true,
                    null,
                    Long.parseLong(normalized)
            );
        } catch (NumberFormatException ignored) {
            return new ExecutionSearch(true, null, null);
        }
    }

    @Transactional(readOnly = true)
    public WorkflowExecutionDetailDTO getExecution(
            UUID workflowId,
            UUID executionId
    ) {
        WorkflowExecution execution = workflowExecutionRepository
                .findById(executionId)
                .filter(candidate ->
                        candidate.getWorkflow().getId().equals(workflowId))
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow execution does not exist"
                ));
        List<WorkflowExecutionScopeDTO> scopes = executionScopeRepository
                .findByWorkflowExecutionOrderByScopePathAsc(execution)
                .stream()
                .map(this::toScope)
                .toList();
        return new WorkflowExecutionDetailDTO(toSummary(execution), scopes);
    }

    private WorkflowExecutionScopeDTO toScope(ExecutionScope scope) {
        return new WorkflowExecutionScopeDTO(
                scope.getId(),
                scope.getParentScope() == null
                        ? null
                        : scope.getParentScope().getId(),
                scope.getScopeType(),
                scope.getScopePath(),
                scope.getOwnerStateName(),
                scope.getBranchIndex(),
                scope.getItemIndex(),
                scope.getStatus(),
                scope.getCurrentStateName(),
                readJson(scope.getCurrentStateInput()),
                readJson(scope.getVariables()),
                readJson(scope.getOutput()),
                scope.getWakeAt(),
                scope.getError(),
                scope.getCause(),
                scope.getStartedAt(),
                scope.getCompletedAt(),
                scope.getCreatedAt(),
                scope.getUpdatedAt(),
                stateExecutionRepository
                        .findByExecutionScopeOrderBySequenceNumberAsc(scope)
                        .stream()
                        .map(this::toStateExecution)
                        .toList()
        );
    }

    private WorkflowStateExecutionDTO toStateExecution(
            StateExecution state
    ) {
        return new WorkflowStateExecutionDTO(
                state.getId(),
                state.getSequenceNumber(),
                state.getStateName(),
                state.getStateType(),
                state.getStatus(),
                state.getResource(),
                readJson(state.getInput()),
                readJson(state.getOutput()),
                state.getRetryAt(),
                state.getError(),
                state.getCause(),
                state.getStartedAt(),
                state.getCompletedAt(),
                state.getCreatedAt(),
                state.getUpdatedAt(),
                attemptRepository
                        .findByStateExecutionOrderByAttemptNumberAsc(state)
                        .stream()
                        .map(this::toAttempt)
                        .toList()
        );
    }

    private WorkflowStateExecutionAttemptDTO toAttempt(
            StateExecutionAttempt attempt
    ) {
        return new WorkflowStateExecutionAttemptDTO(
                attempt.getId(),
                attempt.getAttemptNumber(),
                attempt.getStatus(),
                readJson(attempt.getArguments()),
                readJson(attempt.getResult()),
                attempt.getWorkerId(),
                attempt.getAvailableAt(),
                attempt.getQueuedAt(),
                attempt.getStartedAt(),
                attempt.getHeartbeatAt(),
                attempt.getTimeoutSeconds(),
                attempt.getHeartbeatSeconds(),
                attempt.getTimeoutAt(),
                attempt.getHeartbeatDeadlineAt(),
                attempt.getCompletedAt(),
                attempt.getDurationMs(),
                attempt.getError(),
                attempt.getCause(),
                attempt.getDispatchAttemptCount(),
                attempt.getLastDispatchError(),
                attempt.getCreatedAt(),
                attempt.getUpdatedAt()
        );
    }

    private WorkflowExecutionSummaryDTO toSummary(
            WorkflowExecution execution
    ) {
        return new WorkflowExecutionSummaryDTO(
                execution.getId(),
                execution.getWorkflow().getId(),
                execution.getWorkflowDefinition().getId(),
                execution.getWorkflowDefinition().getRevision(),
                execution.getRunNumber(),
                execution.getStatus(),
                execution.getScheduledFor(),
                readJson(execution.getInput()),
                readJson(execution.getOutput()),
                execution.getError(),
                execution.getCause(),
                execution.getDeadlineAt(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                execution.getCreatedAt(),
                execution.getUpdatedAt()
        );
    }

    private Workflow findWorkflow(UUID workflowId) {
        return workflowRepository.findById(workflowId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow does not exist"
                ));
    }

    private JsonNode readJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read persisted workflow execution JSON",
                    exception
            );
        }
    }

    private record ExecutionSearch(
            boolean provided,
            UUID executionId,
            Long runNumber
    ) {
    }
}
