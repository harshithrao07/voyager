package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.repository.ExecutionScopeRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Foundation for nested (Parallel/Map) execution. Owns the rules the
 * compound-state runtime relies on: creating child scopes idempotently, giving
 * each child an isolated variable snapshot, detecting when a fork's children
 * have settled, resuming the parent exactly once, and canceling unfinished
 * siblings.
 *
 * <p>Each fork of a compound state is a generation, identified by the
 * sequence number of the owning fork's {@code StateExecution}. The generation
 * is embedded in the child scope path so a retry re-forks into fresh child
 * scopes without colliding with the failed generation, and so the children of
 * one generation can be found by path prefix.
 *
 * <p>The interpreter drives any single scope generically; this coordinator is
 * the only place that reasons across a parent and its children.
 */
@Service
public class ExecutionScopeCoordinator {
    private final ExecutionScopeRepository executionScopeRepository;
    private final AslDefinitionNavigator definitionNavigator;
    private final ObjectMapper objectMapper;
    private final WorkflowPayloadLimits payloadLimits;

    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionScopeCoordinator(
            ExecutionScopeRepository executionScopeRepository,
            AslDefinitionNavigator definitionNavigator,
            ObjectMapper objectMapper,
            WorkflowPayloadLimits payloadLimits
    ) {
        this.executionScopeRepository = executionScopeRepository;
        this.definitionNavigator = definitionNavigator;
        this.objectMapper = objectMapper;
        this.payloadLimits = payloadLimits;
    }

    public ExecutionScopeCoordinator(
            ExecutionScopeRepository executionScopeRepository,
            AslDefinitionNavigator definitionNavigator,
            ObjectMapper objectMapper
    ) {
        this(
                executionScopeRepository,
                definitionNavigator,
                objectMapper,
                WorkflowPayloadLimits.defaults(objectMapper)
        );
    }

    /**
     * Creates one Parallel branch scope for a fork generation, or returns the
     * existing one when a scheduler or worker retry replays the fork.
     * Idempotency is anchored to the durable, per-execution-unique scope path.
     */
    @Transactional
    public ExecutionScope forkBranch(
            ExecutionScope parent,
            String ownerStateName,
            long generation,
            int branchIndex,
            JsonNode input
    ) {
        String scopePath = generationPrefix(parent, ownerStateName, generation)
                + "branch-" + branchIndex;
        return executionScopeRepository
                .findByWorkflowExecutionAndScopePath(
                        parent.getWorkflowExecution(),
                        scopePath
                )
                .orElseGet(() -> {
                    ExecutionScope child = newChild(
                            parent,
                            ExecutionScopeType.PARALLEL_BRANCH,
                            ownerStateName,
                            scopePath,
                            input
                    );
                    child.setBranchIndex(branchIndex);
                    child.setCurrentStateName(definitionNavigator.startAt(child));
                    return executionScopeRepository.save(child);
                });
    }

    /**
     * Creates one Map iteration scope for a fork generation, or returns the
     * existing one when a fork is replayed. Iterations of the same Map share one
     * ItemProcessor machine; the iteration identity lives on the scope (item
     * index). Used by the Map runtime (Phase 3).
     */
    @Transactional
    public ExecutionScope forkIteration(
            ExecutionScope parent,
            String ownerStateName,
            long generation,
            long itemIndex,
            JsonNode input,
            JsonNode rawItemValue
    ) {
        String scopePath = generationPrefix(parent, ownerStateName, generation)
                + "item-" + itemIndex;
        return executionScopeRepository
                .findByWorkflowExecutionAndScopePath(
                        parent.getWorkflowExecution(),
                        scopePath
                )
                .orElseGet(() -> {
                    ExecutionScope child = newChild(
                            parent,
                            ExecutionScopeType.MAP_ITERATION,
                            ownerStateName,
                            scopePath,
                            input
                    );
                    child.setItemIndex(itemIndex);
                    if (rawItemValue != null) {
                        child.setItemValue(payloadLimits.serialize(
                                rawItemValue,
                                WorkflowPayloadLimits.Kind.INPUT
                        ));
                    }
                    child.setCurrentStateName(definitionNavigator.startAt(child));
                    return executionScopeRepository.save(child);
                });
    }

    private ExecutionScope newChild(
            ExecutionScope parent,
            ExecutionScopeType scopeType,
            String ownerStateName,
            String scopePath,
            JsonNode input
    ) {
        ExecutionScope child = new ExecutionScope();
        child.setWorkflowExecution(parent.getWorkflowExecution());
        child.setParentScope(parent);
        child.setScopeType(scopeType);
        child.setOwnerStateName(ownerStateName);
        child.setScopePath(scopePath);
        child.setStatus(ExecutionScopeStatus.PENDING);
        String inherited = inheritedVariables(parent);
        payloadLimits.validate(
                inherited,
                WorkflowPayloadLimits.Kind.VARIABLES
        );
        child.setVariables(inherited);
        child.setCurrentStateInput(payloadLimits.serialize(
                input,
                WorkflowPayloadLimits.Kind.INPUT
        ));
        return child;
    }

    /**
     * Variable inheritance and isolation: a child receives a value snapshot of
     * the parent's variables. Because variables are persisted as a JSON document
     * per scope row, a child's later assignments never reach the parent or a
     * sibling, and the parent only ever reads a child's output, never its
     * variables.
     */
    public String inheritedVariables(ExecutionScope parent) {
        String variables = parent.getVariables();
        return variables == null ? "{}" : variables;
    }

    /**
     * All child scopes belonging to one fork generation, found by path prefix.
     */
    public List<ExecutionScope> generationChildren(
            ExecutionScope parent,
            String ownerStateName,
            long generation
    ) {
        return executionScopeRepository
                .findByParentScopeAndScopePathStartingWithOrderByScopePathAsc(
                        parent,
                        generationPrefix(parent, ownerStateName, generation)
                );
    }

    /**
     * Reports how a fork's children stand: whether all reached a terminal
     * status, whether any failed, and their outputs in index order. Output
     * positions for failed or canceled children are {@code null}.
     */
    public ChildSettlement settle(List<ExecutionScope> children) {
        List<ExecutionScope> ordered = new ArrayList<>(children);
        ordered.sort(Comparator.comparingLong(this::orderKey));

        boolean allSettled = true;
        boolean anyFailed = false;
        Integer firstFailedPosition = null;
        String firstError = null;
        String firstCause = null;
        List<JsonNode> orderedOutputs = new ArrayList<>(ordered.size());

        for (int position = 0; position < ordered.size(); position++) {
            ExecutionScope child = ordered.get(position);
            switch (child.getStatus()) {
                case SUCCEEDED -> orderedOutputs.add(readJson(child.getOutput()));
                case FAILED, TIMED_OUT -> {
                    orderedOutputs.add(null);
                    if (!anyFailed) {
                        anyFailed = true;
                        firstFailedPosition = position;
                        firstError = child.getError();
                        firstCause = child.getCause();
                    }
                }
                case CANCELED -> orderedOutputs.add(null);
                default -> {
                    allSettled = false;
                    orderedOutputs.add(null);
                }
            }
        }

        return new ChildSettlement(
                ordered.size(),
                allSettled,
                anyFailed,
                firstFailedPosition,
                firstError,
                firstCause,
                orderedOutputs
        );
    }

    /**
     * Resumes the waiting parent compound scope when a child settles, once and
     * only once per readiness event (pessimistic lock plus WAITING guard).
     *
     * <ul>
     *   <li>A failed Parallel branch fails the whole Parallel: its still-running
     *       siblings are canceled and the parent re-enters immediately.</li>
     *   <li>Otherwise (a Parallel branch success, or any Map iteration result)
     *       the parent re-enters only when every forked sibling of the current
     *       generation has settled. For Map this drives wave-by-wave execution
     *       bounded by MaxConcurrency, and lets the join decide tolerance —
     *       a failed iteration counts as settled rather than failing fast.</li>
     * </ul>
     *
     * @return the parent scope id to advance, or empty when the parent is not
     *         the one to resume now.
     */
    @Transactional
    public Optional<UUID> onChildSettled(UUID childScopeId) {
        ExecutionScope child = executionScopeRepository
                .findByIdForUpdate(childScopeId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Child execution scope does not exist"
                ));
        ExecutionScope parent = lockParent(child);
        if (parent == null || parent.getStatus() != ExecutionScopeStatus.WAITING) {
            return Optional.empty();
        }
        boolean failed = child.getStatus() == ExecutionScopeStatus.FAILED
                || child.getStatus() == ExecutionScopeStatus.TIMED_OUT;
        if (child.getScopeType() == ExecutionScopeType.PARALLEL_BRANCH && failed) {
            cancelUnfinished(siblings(child));
            return Optional.of(readyParent(parent));
        }
        if (!settle(siblings(child)).allSettled()) {
            return Optional.empty();
        }
        return Optional.of(readyParent(parent));
    }

    /**
     * Cancels every still-running child of one fork generation. Used by a Map
     * join that has decided to fail (tolerated-failure threshold exceeded, or an
     * untolerated iteration failure) so surviving iterations stop being driven.
     */
    @Transactional
    public int cancelGeneration(
            ExecutionScope parent,
            String ownerStateName,
            long generation
    ) {
        List<ExecutionScope> children =
                generationChildren(parent, ownerStateName, generation);
        int before = (int) children.stream()
                .filter(child -> !isTerminal(child.getStatus()))
                .count();
        cancelUnfinished(children);
        return before;
    }

    private ExecutionScope lockParent(ExecutionScope child) {
        if (child.getParentScope() == null) {
            return null;
        }
        return executionScopeRepository
                .findByIdForUpdate(child.getParentScope().getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Parent execution scope does not exist"
                ));
    }

    private UUID readyParent(ExecutionScope parent) {
        parent.setStatus(ExecutionScopeStatus.RUNNING);
        parent.setWakeAt(null);
        executionScopeRepository.save(parent);
        return parent.getId();
    }

    private void cancelUnfinished(List<ExecutionScope> scopes) {
        Instant now = Instant.now();
        for (ExecutionScope scope : scopes) {
            if (!isTerminal(scope.getStatus())) {
                scope.setStatus(ExecutionScopeStatus.CANCELED);
                scope.setCompletedAt(now);
                executionScopeRepository.save(scope);
            }
        }
    }

    private List<ExecutionScope> siblings(ExecutionScope child) {
        String path = child.getScopePath();
        String prefix = path.substring(0, path.lastIndexOf('/') + 1);
        return executionScopeRepository
                .findByParentScopeAndScopePathStartingWithOrderByScopePathAsc(
                        child.getParentScope(),
                        prefix
                );
    }

    private String generationPrefix(
            ExecutionScope parent,
            String ownerStateName,
            long generation
    ) {
        return parent.getScopePath()
                + "/" + ownerStateName + "/g" + generation + "/";
    }

    private boolean isTerminal(ExecutionScopeStatus status) {
        return status == ExecutionScopeStatus.SUCCEEDED
                || status == ExecutionScopeStatus.FAILED
                || status == ExecutionScopeStatus.CANCELED
                || status == ExecutionScopeStatus.TIMED_OUT;
    }

    private long orderKey(ExecutionScope scope) {
        if (scope.getBranchIndex() != null) {
            return scope.getBranchIndex();
        }
        if (scope.getItemIndex() != null) {
            return scope.getItemIndex();
        }
        return 0L;
    }

    private JsonNode readJson(String value) {
        try {
            return value == null
                    ? objectMapper.nullNode()
                    : objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read persisted execution scope JSON",
                    exception
            );
        }
    }

    /**
     * Snapshot of one fork generation's children.
     *
     * @param total               number of child scopes in the generation
     * @param allSettled          true when every child reached a terminal status
     * @param anyFailed           true when at least one child FAILED
     * @param firstFailedPosition index-ordered position of the first failed child
     * @param firstError          ASL error name of the first failed child
     * @param firstCause          cause of the first failed child
     * @param orderedOutputs      child outputs in index order; null per position
     *                            for failed or canceled children
     */
    public record ChildSettlement(
            int total,
            boolean allSettled,
            boolean anyFailed,
            Integer firstFailedPosition,
            String firstError,
            String firstCause,
            List<JsonNode> orderedOutputs
    ) {
    }
}
