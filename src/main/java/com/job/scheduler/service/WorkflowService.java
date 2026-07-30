package com.job.scheduler.service;

import com.job.scheduler.dto.CreateWorkflowRequestDTO;
import com.job.scheduler.dto.CreateWorkflowRevisionRequestDTO;
import com.job.scheduler.dto.WorkflowDefinitionResponseDTO;
import com.job.scheduler.dto.WorkflowResponseDTO;
import com.job.scheduler.dto.WorkflowPageDTO;
import com.job.scheduler.dto.UpdateWorkflowMetadataRequestDTO;
import com.job.scheduler.dto.UpdateWorkflowCanvasLayoutRequestDTO;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.repository.WorkflowDefinitionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import com.job.scheduler.workflow.asl.runtime.AslRuntimeCapabilityValidator;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidationException;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslFunctionResourceValidator;
import com.job.scheduler.workflow.asl.validation.AslMcpResourceValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowService {
    private static final String DEFAULT_TIMEZONE = "UTC";
    private static final int MAX_PAGE_SIZE = 100;
    private static final double MAX_CANVAS_COORDINATE = 1_000_000.0;

    private final WorkflowRepository workflowRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final AslDefinitionValidator aslDefinitionValidator;
    private final WorkflowDefinitionCanonicalizer definitionCanonicalizer;
    private final ObjectMapper objectMapper;
    private final AslRuntimeCapabilityValidator runtimeCapabilityValidator;
    private final AslMcpResourceValidator mcpResourceValidator;
    private final AslFunctionResourceValidator functionResourceValidator;

    @Transactional
    public WorkflowResponseDTO createWorkflow(CreateWorkflowRequestDTO request) {
        Workflow existing = workflowRepository.findByIdempotencyKey(request.idempotencyKey())
                .orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }

        String timezone = normalizeTimezone(request.timezone());
        String cronExpression = normalizeNullable(request.cronExpression());
        parseCronExpression(cronExpression);
        WorkflowDefinitionCanonicalizer.CanonicalWorkflowDefinition canonical =
                definitionCanonicalizer.canonicalize(request.definition());

        Workflow workflow = new Workflow();
        workflow.setName(request.name().trim());
        workflow.setStatus(WorkflowStatus.DRAFT);
        workflow.setCronExpression(cronExpression);
        workflow.setTimezone(timezone);
        workflow.setScheduledInput("{}");
        workflow.setMaxAttempts(
                request.maxAttempts() == null
                        ? workflow.getMaxAttempts()
                        : request.maxAttempts()
        );
        workflow.setIdempotencyKey(request.idempotencyKey().trim());
        workflow.setNextRunAt(null);
        workflowRepository.save(workflow);

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflow(workflow);
        definition.setRevision(1);
        definition.setDefinition(canonical.json());
        definition.setDefinitionHash(canonical.hash());
        workflowDefinitionRepository.save(definition);

        // Manual workflows have no schedule to activate. A successful save makes
        // the validated definition executable immediately. Recurring workflows
        // remain unscheduled until their revision is explicitly activated.
        if (isManual(workflow)) {
            activateDefinition(workflow, definition);
        }

        return toResponse(workflow);
    }

    @Transactional
    public WorkflowDefinitionResponseDTO createRevision(
            UUID workflowId,
            CreateWorkflowRevisionRequestDTO request
    ) {
        Workflow workflow = findForUpdate(workflowId);
        WorkflowDefinitionCanonicalizer.CanonicalWorkflowDefinition canonical =
                definitionCanonicalizer.canonicalize(request.definition());

        WorkflowDefinition duplicate = workflowDefinitionRepository
                .findByWorkflowAndDefinitionHash(workflow, canonical.hash())
                .orElse(null);
        if (duplicate != null) {
            if (shouldActivateOnSave(workflow, request.activate())) {
                activateDefinition(workflow, duplicate);
            }
            return toDefinitionResponse(workflow, duplicate);
        }

        long nextRevision = workflowDefinitionRepository
                .findFirstByWorkflowOrderByRevisionDesc(workflow)
                .map(latest -> latest.getRevision() + 1)
                .orElse(1L);

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflow(workflow);
        definition.setRevision(nextRevision);
        definition.setDefinition(canonical.json());
        definition.setDefinitionHash(canonical.hash());
        workflowDefinitionRepository.save(definition);

        if (shouldActivateOnSave(workflow, request.activate())) {
            activateDefinition(workflow, definition);
        }

        return toDefinitionResponse(workflow, definition);
    }

    public WorkflowResponseDTO getWorkflow(UUID workflowId) {
        return toResponse(findWorkflow(workflowId));
    }

    public WorkflowPageDTO listWorkflows(
            int page,
            int size,
            WorkflowStatus status,
            String name
    ) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Specification<Workflow> specification =
                (root, query, builder) -> builder.conjunction();
        if (status != null) {
            specification = specification.and(
                    (root, query, builder) ->
                            builder.equal(root.get("status"), status)
            );
        }
        String normalizedName = normalizeNullable(name);
        if (normalizedName != null) {
            String pattern = "%"
                    + normalizedName.toLowerCase(java.util.Locale.ROOT)
                    + "%";
            specification = specification.and(
                    (root, query, builder) ->
                            builder.like(
                                    builder.lower(root.get("name")),
                                    pattern
                            )
            );
        }
        Page<Workflow> workflows = workflowRepository.findAll(
                specification,
                PageRequest.of(
                        normalizedPage,
                        normalizedSize,
                        Sort.by(Sort.Direction.DESC, "createdAt")
                )
        );
        return new WorkflowPageDTO(
                workflows.getContent().stream()
                        .map(this::toResponse)
                        .toList(),
                workflows.getNumber(),
                workflows.getSize(),
                workflows.getTotalElements(),
                workflows.getTotalPages(),
                workflows.isFirst(),
                workflows.isLast()
        );
    }

    @Transactional
    public WorkflowResponseDTO updateMetadata(
            UUID workflowId,
            UpdateWorkflowMetadataRequestDTO request
    ) {
        Workflow workflow = findWorkflow(workflowId);
        if (workflow.getVersion() != request.expectedVersion()) {
            throw new IllegalStateException(
                    "Workflow version conflict: expected "
                            + request.expectedVersion()
                            + " but was "
                            + workflow.getVersion()
            );
        }
        ensureNotArchived(workflow);
        boolean changed = false;
        boolean schedulingChanged = false;

        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(
                        "Workflow name cannot be blank"
                );
            }
            workflow.setName(name);
            changed = true;
        }
        if (request.cronExpression() != null) {
            if (!request.cronExpression().isNull()
                    && !request.cronExpression().isString()) {
                throw new IllegalArgumentException(
                        "Cron expression must be a string or null"
                );
            }
            String cronExpression = request.cronExpression().isNull()
                    ? null
                    : normalizeNullable(
                            request.cronExpression().stringValue()
                    );
            parseCronExpression(cronExpression);
            workflow.setCronExpression(cronExpression);
            changed = true;
            schedulingChanged = true;
        }
        if (request.scheduledInput() != null) {
            workflow.setScheduledInput(writeJson(
                    request.scheduledInput(),
                    "scheduled workflow input"
            ));
            changed = true;
        }
        if (request.timezone() != null) {
            workflow.setTimezone(normalizeTimezone(request.timezone()));
            changed = true;
            schedulingChanged = true;
        }
        if (request.maxAttempts() != null) {
            workflow.setMaxAttempts(request.maxAttempts());
            changed = true;
        }
        if (!changed) {
            throw new IllegalArgumentException(
                    "At least one workflow metadata field must be supplied"
            );
        }
        if (schedulingChanged) {
            workflow.setNextRunAt(
                    workflow.getStatus() == WorkflowStatus.ACTIVE
                            ? calculateNextRunAt(
                                    workflow.getCronExpression(),
                                    workflow.getTimezone()
                            )
                            : null
            );
        }
        return toResponse(workflowRepository.saveAndFlush(workflow));
    }

    public List<WorkflowDefinitionResponseDTO> getRevisions(UUID workflowId) {
        Workflow workflow = findWorkflow(workflowId);
        return workflowDefinitionRepository.findByWorkflowOrderByRevisionDesc(workflow)
                .stream()
                .map(definition -> toDefinitionResponse(workflow, definition))
                .toList();
    }

    @Transactional
    public WorkflowDefinitionResponseDTO updateCanvasLayout(
            UUID workflowId,
            long revision,
            UpdateWorkflowCanvasLayoutRequestDTO request
    ) {
        Workflow workflow = findForUpdate(workflowId);
        ensureNotArchived(workflow);
        WorkflowDefinition definition = workflowDefinitionRepository
                .findByWorkflowAndRevision(workflow, revision)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow definition revision does not exist"
                ));
        JsonNode canvasLayout = normalizeCanvasLayout(
                definition,
                request.positions()
        );
        definition.setCanvasLayout(writeJson(
                canvasLayout,
                "workflow canvas layout"
        ));
        workflowDefinitionRepository.saveAndFlush(definition);
        return toDefinitionResponse(workflow, definition);
    }

    @Transactional
    public WorkflowDefinitionResponseDTO activateRevision(UUID workflowId, long revision) {
        Workflow workflow = findForUpdate(workflowId);
        ensureNotArchived(workflow);
        WorkflowDefinition definition = workflowDefinitionRepository
                .findByWorkflowAndRevision(workflow, revision)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow definition revision does not exist"
                ));
        activateDefinition(workflow, definition);
        return toDefinitionResponse(workflow, definition);
    }

    @Transactional
    public WorkflowResponseDTO pauseWorkflow(UUID workflowId) {
        Workflow workflow = findForUpdate(workflowId);
        if (workflow.getStatus() == WorkflowStatus.PAUSED) {
            return toResponse(workflow);
        }
        ensureNotArchived(workflow);
        if (workflow.getStatus() != WorkflowStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only ACTIVE workflows can be paused"
            );
        }
        workflow.setStatus(WorkflowStatus.PAUSED);
        workflow.setNextRunAt(null);
        workflowRepository.save(workflow);
        return toResponse(workflow);
    }

    @Transactional
    public WorkflowResponseDTO resumeWorkflow(UUID workflowId) {
        Workflow workflow = findForUpdate(workflowId);
        if (workflow.getStatus() == WorkflowStatus.ACTIVE) {
            return toResponse(workflow);
        }
        ensureNotArchived(workflow);
        if (workflow.getStatus() != WorkflowStatus.PAUSED) {
            throw new IllegalStateException(
                    "Only PAUSED workflows can be resumed"
            );
        }
        if (workflow.getActiveDefinition() == null) {
            throw new IllegalStateException(
                    "Workflow has no active definition"
            );
        }
        validateExecutableDefinition(readDefinition(
                workflow.getActiveDefinition()
        ));
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setNextRunAt(calculateNextRunAt(
                workflow.getCronExpression(),
                workflow.getTimezone()
        ));
        workflowRepository.save(workflow);
        return toResponse(workflow);
    }

    @Transactional
    public WorkflowResponseDTO archiveWorkflow(UUID workflowId) {
        Workflow workflow = findForUpdate(workflowId);
        if (workflow.getStatus() == WorkflowStatus.ARCHIVED) {
            return toResponse(workflow);
        }
        workflow.setStatus(WorkflowStatus.ARCHIVED);
        workflow.setNextRunAt(null);
        workflowRepository.save(workflow);
        return toResponse(workflow);
    }

    private void activateDefinition(
            Workflow workflow,
            WorkflowDefinition definition
    ) {
        ensureNotArchived(workflow);
        validateExecutableDefinition(readDefinition(definition));
        workflow.setActiveDefinition(definition);
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setNextRunAt(calculateNextRunAt(
                workflow.getCronExpression(),
                workflow.getTimezone()
        ));
        workflowRepository.save(workflow);
    }

    private boolean shouldActivateOnSave(
            Workflow workflow,
            boolean activationRequested
    ) {
        return isManual(workflow) || activationRequested;
    }

    private boolean isManual(Workflow workflow) {
        return normalizeNullable(workflow.getCronExpression()) == null;
    }

    private void ensureNotArchived(Workflow workflow) {
        if (workflow.getStatus() == WorkflowStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Archived workflows cannot be reactivated"
            );
        }
    }

    private Instant calculateNextRunAt(
            String cronExpression,
            String timezone
    ) {
        CronExpression parsed = parseCronExpression(cronExpression);
        return parsed == null ? null : nextRunAt(parsed, timezone);
    }

    private void validateExecutableDefinition(JsonNode definition) {
        AslValidationResult validation = aslDefinitionValidator.validate(definition);
        if (!validation.isExecutable()) {
            throw new AslDefinitionValidationException(validation.issues());
        }
        var runtimeIssues = runtimeCapabilityValidator.validate(definition);
        if (!runtimeIssues.isEmpty()) {
            throw new AslDefinitionValidationException(runtimeIssues);
        }
        var mcpIssues = mcpResourceValidator.validate(definition);
        if (!mcpIssues.isEmpty()) {
            throw new AslDefinitionValidationException(mcpIssues);
        }
        var functionIssues = functionResourceValidator.validate(definition);
        if (!functionIssues.isEmpty()) {
            throw new AslDefinitionValidationException(functionIssues);
        }
    }

    private Workflow findWorkflow(UUID workflowId) {
        return workflowRepository.findById(workflowId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow does not exist"));
    }

    private Workflow findForUpdate(UUID workflowId) {
        return workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow does not exist"));
    }

    private WorkflowResponseDTO toResponse(Workflow workflow) {
        return new WorkflowResponseDTO(
                workflow.getId(),
                workflow.getVersion(),
                workflow.getName(),
                workflow.getStatus(),
                workflow.getCronExpression(),
                workflow.getTimezone(),
                workflow.getNextRunAt(),
                readScheduledInput(workflow),
                workflow.getMaxAttempts(),
                workflow.getIdempotencyKey(),
                workflow.getActiveDefinition() == null
                        ? null
                        : toDefinitionResponse(workflow, workflow.getActiveDefinition()),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt()
        );
    }

    private WorkflowDefinitionResponseDTO toDefinitionResponse(
            Workflow workflow,
            WorkflowDefinition definition
    ) {
        return new WorkflowDefinitionResponseDTO(
                definition.getId(),
                definition.getRevision(),
                definition.getDefinitionHash(),
                readDefinition(definition),
                readCanvasLayout(definition),
                workflow.getActiveDefinition() != null
                        && workflow.getActiveDefinition().getId().equals(definition.getId()),
                definition.getCreatedAt()
        );
    }

    private JsonNode readDefinition(WorkflowDefinition definition) {
        try {
            return objectMapper.readTree(definition.getDefinition());
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read persisted ASL definition", exception);
        }
    }

    private JsonNode readCanvasLayout(WorkflowDefinition definition) {
        if (definition.getCanvasLayout() == null
                || definition.getCanvasLayout().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode layout = objectMapper.readTree(definition.getCanvasLayout());
            return layout != null && layout.isObject()
                    ? layout
                    : objectMapper.createObjectNode();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read persisted workflow canvas layout",
                    exception
            );
        }
    }

    private JsonNode readScheduledInput(Workflow workflow) {
        if (workflow.getScheduledInput() == null
                || workflow.getScheduledInput().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(workflow.getScheduledInput());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read persisted scheduled workflow input",
                    exception
            );
        }
    }

    private JsonNode normalizeCanvasLayout(
            WorkflowDefinition definition,
            JsonNode positions
    ) {
        if (!positions.isObject()) {
            throw new IllegalArgumentException(
                    "Canvas positions must be an object keyed by state name"
            );
        }
        JsonNode workflowDefinition = readDefinition(definition);
        JsonNode states = workflowDefinition.path("States");
        if (positions.size() > countStates(workflowDefinition)) {
            throw new IllegalArgumentException(
                    "Canvas positions contain more entries than the workflow definition"
            );
        }

        var normalized = objectMapper.createObjectNode();
        for (var entry : positions.properties()) {
            String stateName = entry.getKey();
            JsonNode position = entry.getValue();
            boolean rootState = states.has(stateName);
            boolean scopedState = stateName.startsWith("@scope/")
                    && workflowDefinition.at(stateName.substring("@scope".length())).isObject()
                    && workflowDefinition.at(stateName.substring("@scope".length())).has("Type");
            if (!rootState && !scopedState) {
                throw new IllegalArgumentException(
                        "Canvas position references an unknown state: " + stateName
                );
            }
            if (!position.isObject()
                    || !position.path("x").isNumber()
                    || !position.path("y").isNumber()) {
                throw new IllegalArgumentException(
                        "Canvas position for " + stateName
                                + " must contain numeric x and y values"
                );
            }
            double x = position.path("x").doubleValue();
            double y = position.path("y").doubleValue();
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || Math.abs(x) > MAX_CANVAS_COORDINATE
                    || Math.abs(y) > MAX_CANVAS_COORDINATE) {
                throw new IllegalArgumentException(
                        "Canvas position for " + stateName + " is outside the allowed range"
                );
            }
            normalized.set(
                    stateName,
                    objectMapper.createObjectNode()
                            .put("x", x)
                            .put("y", y)
            );
        }
        return normalized;
    }

    private int countStates(JsonNode machine) {
        JsonNode states = machine.path("States");
        if (!states.isObject()) {
            return 0;
        }

        int count = states.size();
        for (var stateEntry : states.properties()) {
            JsonNode state = stateEntry.getValue();
            if ("Parallel".equals(state.path("Type").stringValue())
                    && state.path("Branches").isArray()) {
                for (JsonNode branch : state.path("Branches")) {
                    count += countStates(branch);
                }
            }
            if ("Map".equals(state.path("Type").stringValue())
                    && state.path("ItemProcessor").isObject()) {
                count += countStates(state.path("ItemProcessor"));
            }
        }
        return count;
    }

    private String writeJson(JsonNode value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not persist " + label, exception);
        }
    }

    private String normalizeTimezone(String timezone) {
        String normalized =
                timezone == null || timezone.isBlank()
                        ? DEFAULT_TIMEZONE
                        : timezone.trim();
        try {
            return ZoneId.of(normalized).getId();
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Workflow timezone is invalid: " + normalized,
                    exception
            );
        }
    }

    private CronExpression parseCronExpression(String cronExpression) {
        String normalized = normalizeNullable(cronExpression);
        if (normalized == null) {
            return null;
        }
        try {
            return CronExpression.parse(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid workflow cron expression: " + cronExpression,
                    exception
            );
        }
    }

    private Instant nextRunAt(CronExpression cronExpression, String timezone) {
        ZonedDateTime nextRun = cronExpression.next(ZonedDateTime.now(ZoneId.of(timezone)));
        if (nextRun == null) {
            throw new IllegalArgumentException(
                    "Workflow cron expression does not produce a future run"
            );
        }
        return nextRun.toInstant();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
