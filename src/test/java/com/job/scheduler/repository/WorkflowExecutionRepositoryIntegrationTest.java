package com.job.scheduler.repository;

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
import com.job.scheduler.enums.WorkflowStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.job.scheduler.service.WorkflowExecutionCancellationService.class)
class WorkflowExecutionRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("jobscheduler")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Autowired
    private WorkflowExecutionRepository workflowExecutionRepository;

    @Autowired
    private ExecutionScopeRepository executionScopeRepository;

    @Autowired
    private StateExecutionRepository stateExecutionRepository;

    @Autowired
    private StateExecutionAttemptRepository stateExecutionAttemptRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private com.job.scheduler.service.WorkflowExecutionCancellationService
            workflowExecutionCancellationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void persistsExecutionPinnedToDefinitionAndFindsScheduledOccurrence() {
        Workflow workflow = saveWorkflow("workflow-execution-1");
        WorkflowDefinition definition = saveDefinition(workflow);
        Instant scheduledFor = Instant.parse("2026-06-21T06:00:00Z");

        WorkflowExecution execution = execution(
                workflow,
                definition,
                1,
                scheduledFor
        );
        execution.setInput(OBJECT_MAPPER.createObjectNode()
                .put("orderId", "order-100")
                .toString());

        WorkflowExecution saved =
                workflowExecutionRepository.saveAndFlush(execution);
        entityManager.clear();

        WorkflowExecution reloaded = workflowExecutionRepository
                .findByWorkflowAndScheduledFor(workflow, scheduledFor)
                .orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getWorkflowDefinition().getId())
                .isEqualTo(definition.getId());
        assertThat(reloaded.getRunNumber()).isEqualTo(1);
        assertThat(reloaded.getStatus())
                .isEqualTo(WorkflowExecutionStatus.PENDING);
        assertThat(OBJECT_MAPPER.readTree(reloaded.getInput()).get("orderId").stringValue())
                .isEqualTo("order-100");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateRunNumberForSameWorkflow() {
        Workflow workflow = saveWorkflow("workflow-execution-2");
        WorkflowDefinition definition = saveDefinition(workflow);

        workflowExecutionRepository.saveAndFlush(execution(
                workflow,
                definition,
                1,
                Instant.parse("2026-06-21T06:00:00Z")
        ));

        assertThatThrownBy(() -> workflowExecutionRepository.saveAndFlush(execution(
                workflow,
                definition,
                1,
                Instant.parse("2026-06-22T06:00:00Z")
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateScheduledOccurrenceForSameWorkflow() {
        Workflow workflow = saveWorkflow("workflow-execution-3");
        WorkflowDefinition definition = saveDefinition(workflow);
        Instant scheduledFor = Instant.parse("2026-06-21T06:00:00Z");

        workflowExecutionRepository.saveAndFlush(
                execution(workflow, definition, 1, scheduledFor)
        );

        assertThatThrownBy(() -> workflowExecutionRepository.saveAndFlush(
                execution(workflow, definition, 2, scheduledFor)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsScopeStateVisitAndRetryAttempts() {
        Workflow workflow = saveWorkflow("workflow-runtime-hierarchy");
        WorkflowDefinition definition = saveDefinition(workflow);
        WorkflowExecution workflowExecution = workflowExecutionRepository.saveAndFlush(
                execution(
                        workflow,
                        definition,
                        1,
                        Instant.parse("2026-06-21T06:00:00Z")
                )
        );

        ExecutionScope root = new ExecutionScope();
        root.setWorkflowExecution(workflowExecution);
        root.setScopeType(ExecutionScopeType.ROOT);
        root.setScopePath("root");
        root.setStatus(ExecutionScopeStatus.RUNNING);
        root.setCurrentStateName("ChargeOrder");
        root.setCurrentStateInput("{\"orderId\":\"order-100\"}");
        root = executionScopeRepository.saveAndFlush(root);

        StateExecution stateExecution = new StateExecution();
        stateExecution.setExecutionScope(root);
        stateExecution.setSequenceNumber(1);
        stateExecution.setStateName("ChargeOrder");
        stateExecution.setStateType(AslStateType.TASK);
        stateExecution.setStatus(StateExecutionStatus.RETRY_WAIT);
        stateExecution.setResource("voyager://payments/charge");
        stateExecution.setInput("{\"orderId\":\"order-100\"}");
        stateExecution = stateExecutionRepository.saveAndFlush(stateExecution);

        StateExecutionAttempt failedAttempt = attempt(
                stateExecution,
                1,
                StateExecutionAttemptStatus.FAILED
        );
        failedAttempt.setError("Payment.Timeout");
        stateExecutionAttemptRepository.saveAndFlush(failedAttempt);

        StateExecutionAttempt successfulAttempt = attempt(
                stateExecution,
                2,
                StateExecutionAttemptStatus.SUCCEEDED
        );
        successfulAttempt.setResult("{\"charged\":true}");
        stateExecutionAttemptRepository.saveAndFlush(successfulAttempt);
        entityManager.clear();

        ExecutionScope reloadedRoot = executionScopeRepository
                .findByWorkflowExecutionAndScopePath(workflowExecution, "root")
                .orElseThrow();
        StateExecution reloadedState = stateExecutionRepository
                .findByExecutionScopeAndSequenceNumber(reloadedRoot, 1)
                .orElseThrow();
        var attempts = stateExecutionAttemptRepository
                .findByStateExecutionOrderByAttemptNumberAsc(reloadedState);

        assertThat(reloadedRoot.getCurrentStateName()).isEqualTo("ChargeOrder");
        assertThat(reloadedState.getStateType()).isEqualTo(AslStateType.TASK);
        assertThat(attempts).extracting(StateExecutionAttempt::getAttemptNumber)
                .containsExactly(1, 2);
        assertThat(attempts).extracting(StateExecutionAttempt::getStatus)
                .containsExactly(
                        StateExecutionAttemptStatus.FAILED,
                        StateExecutionAttemptStatus.SUCCEEDED
                );
    }

    @Test
    void permitsAStateRevisitWithNewSequenceNumber() {
        Workflow workflow = saveWorkflow("workflow-state-loop");
        WorkflowDefinition definition = saveDefinition(workflow);
        WorkflowExecution workflowExecution = workflowExecutionRepository.saveAndFlush(
                execution(
                        workflow,
                        definition,
                        1,
                        Instant.parse("2026-06-21T07:00:00Z")
                )
        );
        ExecutionScope root = rootScope(workflowExecution);

        stateExecutionRepository.saveAndFlush(
                stateExecution(root, 1, "PollStatus")
        );
        stateExecutionRepository.saveAndFlush(
                stateExecution(root, 2, "WaitBeforePolling")
        );
        stateExecutionRepository.saveAndFlush(
                stateExecution(root, 3, "PollStatus")
        );

        var visits = stateExecutionRepository
                .findByExecutionScopeOrderBySequenceNumberAsc(root);

        assertThat(visits).extracting(StateExecution::getStateName)
                .containsExactly(
                        "PollStatus",
                        "WaitBeforePolling",
                        "PollStatus"
                );
    }

    @Test
    void claimsDueWaitingScopeAndLeavesFutureScopeUnclaimed() {
        Workflow workflow = saveWorkflow("workflow-wait-claim");
        WorkflowDefinition definition = saveDefinition(workflow);
        WorkflowExecution workflowExecution = workflowExecutionRepository.saveAndFlush(
                execution(
                        workflow,
                        definition,
                        1,
                        Instant.parse("2026-06-21T08:00:00Z")
                )
        );
        ExecutionScope due = rootScope(workflowExecution);
        due.setStatus(ExecutionScopeStatus.WAITING);
        due.setWakeAt(Instant.now().minusSeconds(1));
        executionScopeRepository.saveAndFlush(due);

        ExecutionScope future = new ExecutionScope();
        future.setWorkflowExecution(workflowExecution);
        future.setScopeType(ExecutionScopeType.MAP_ITERATION);
        future.setScopePath("root/item-1");
        future.setStatus(ExecutionScopeStatus.WAITING);
        future.setWakeAt(Instant.now().plusSeconds(60));
        executionScopeRepository.saveAndFlush(future);
        entityManager.clear();

        Instant now = Instant.now();
        var claimed = executionScopeRepository.claimDueWaitingScopes(
                now,
                now.minus(1, ChronoUnit.MINUTES),
                10
        );

        assertThat(claimed).extracting(ExecutionScope::getId)
                .containsExactly(due.getId());
        assertThat(claimed.get(0).getStatus())
                .isEqualTo(ExecutionScopeStatus.RUNNING);
    }

    @Test
    void claimsOnlyDuePendingTaskAttemptsForDispatch() {
        Workflow workflow = saveWorkflow("workflow-task-attempt-claim");
        WorkflowDefinition definition = saveDefinition(workflow);
        WorkflowExecution workflowExecution = workflowExecutionRepository.saveAndFlush(
                execution(
                        workflow,
                        definition,
                        1,
                        Instant.parse("2026-06-21T09:00:00Z")
                )
        );
        ExecutionScope root = rootScope(workflowExecution);
        StateExecution stateExecution = stateExecutionRepository.saveAndFlush(
                stateExecution(root, 1, "CallTool")
        );

        StateExecutionAttempt due = attempt(
                stateExecution,
                1,
                StateExecutionAttemptStatus.PENDING
        );
        due.setAvailableAt(Instant.now().minusSeconds(1));
        due = stateExecutionAttemptRepository.saveAndFlush(due);

        StateExecutionAttempt future = attempt(
                stateExecution,
                2,
                StateExecutionAttemptStatus.PENDING
        );
        future.setAvailableAt(Instant.now().plusSeconds(60));
        stateExecutionAttemptRepository.saveAndFlush(future);
        entityManager.clear();

        var claimed = stateExecutionAttemptRepository
                .claimDueAttemptsForDispatch(Instant.now(), 10);

        assertThat(claimed).extracting(StateExecutionAttempt::getId)
                .containsExactly(due.getId());
        assertThat(claimed.get(0).getStatus())
                .isEqualTo(StateExecutionAttemptStatus.QUEUED);
        assertThat(claimed.get(0).getQueuedAt()).isNotNull();
        assertThat(claimed.get(0).getDispatchAttemptCount()).isEqualTo(1);
    }

    @Test
    void serializesWorkerClaimAgainstStaleQueuedRecovery() {
        Workflow workflow = saveWorkflow("workflow-queued-recovery-race");
        WorkflowDefinition definition = saveDefinition(workflow);
        WorkflowExecution workflowExecution = workflowExecutionRepository
                .saveAndFlush(execution(
                        workflow,
                        definition,
                        1,
                        Instant.parse("2026-06-21T09:00:00Z")
                ));
        ExecutionScope root = rootScope(workflowExecution);
        StateExecution state = stateExecutionRepository.saveAndFlush(
                stateExecution(root, 1, "CallTool")
        );
        Instant staleQueuedAt = Instant.now().minus(10, ChronoUnit.MINUTES);

        StateExecutionAttempt workerWins = attempt(
                state,
                1,
                StateExecutionAttemptStatus.QUEUED
        );
        workerWins.setQueuedAt(staleQueuedAt);
        workerWins = stateExecutionAttemptRepository.saveAndFlush(workerWins);
        entityManager.clear();

        int claimed = stateExecutionAttemptRepository
                .claimQueuedAttemptForExecution(
                        workerWins.getId(),
                        "worker-1",
                        Instant.now(),
                        StateExecutionAttemptStatus.QUEUED,
                        StateExecutionAttemptStatus.RUNNING
                );
        var recoveredAfterWorker =
                stateExecutionAttemptRepository.recoverStaleQueuedAttempts(
                        Instant.now().minus(5, ChronoUnit.MINUTES),
                        Instant.now(),
                        10
                );

        assertThat(claimed).isEqualTo(1);
        assertThat(recoveredAfterWorker).isEmpty();
        assertThat(stateExecutionAttemptRepository.findById(workerWins.getId())
                .orElseThrow().getStatus())
                .isEqualTo(StateExecutionAttemptStatus.RUNNING);

        StateExecutionAttempt recoveryWins = attempt(
                state,
                2,
                StateExecutionAttemptStatus.QUEUED
        );
        recoveryWins.setQueuedAt(staleQueuedAt);
        recoveryWins =
                stateExecutionAttemptRepository.saveAndFlush(recoveryWins);
        entityManager.clear();

        var recoveredBeforeWorker =
                stateExecutionAttemptRepository.recoverStaleQueuedAttempts(
                        Instant.now().minus(5, ChronoUnit.MINUTES),
                        Instant.now(),
                        10
                );
        int lateWorkerClaim = stateExecutionAttemptRepository
                .claimQueuedAttemptForExecution(
                        recoveryWins.getId(),
                        "worker-2",
                        Instant.now(),
                        StateExecutionAttemptStatus.QUEUED,
                        StateExecutionAttemptStatus.RUNNING
                );

        assertThat(recoveredBeforeWorker)
                .extracting(StateExecutionAttempt::getId)
                .containsExactly(recoveryWins.getId());
        assertThat(lateWorkerClaim).isZero();
        StateExecutionAttempt recovered = stateExecutionAttemptRepository
                .findById(recoveryWins.getId())
                .orElseThrow();
        assertThat(recovered.getStatus())
                .isEqualTo(StateExecutionAttemptStatus.PENDING);
        assertThat(recovered.getQueuedAt()).isNull();
        assertThat(recovered.getAvailableAt()).isNotNull();
    }

    @Test
    void claimsOnlyActiveWorkflowsWhoseScheduleIsDue() {
        Instant now = Instant.now();
        Workflow due = saveWorkflow("workflow-schedule-due");
        due.setNextRunAt(now.minusSeconds(1));
        workflowRepository.saveAndFlush(due);

        Workflow future = saveWorkflow("workflow-schedule-future");
        future.setNextRunAt(now.plusSeconds(60));
        workflowRepository.saveAndFlush(future);

        Workflow paused = saveWorkflow("workflow-schedule-paused");
        paused.setStatus(WorkflowStatus.PAUSED);
        paused.setNextRunAt(now.minusSeconds(1));
        workflowRepository.saveAndFlush(paused);
        entityManager.clear();

        var claimed = workflowRepository.claimDueWorkflows(now, 10);

        assertThat(claimed).extracting(Workflow::getId)
                .containsExactly(due.getId());
    }

    @Test
    void claimsPendingRootForExecutionStartup() {
        Workflow workflow = saveWorkflow("workflow-start-claim");
        WorkflowDefinition definition = saveDefinition(workflow);
        WorkflowExecution pending = workflowExecutionRepository.saveAndFlush(
                execution(
                        workflow,
                        definition,
                        1,
                        Instant.parse("2026-06-21T09:00:00Z")
                )
        );
        ExecutionScope root = rootScope(pending);
        root.setStatus(ExecutionScopeStatus.PENDING);
        executionScopeRepository.saveAndFlush(root);

        WorkflowExecution running = execution(
                workflow,
                definition,
                2,
                Instant.parse("2026-06-21T10:00:00Z")
        );
        running.setStatus(WorkflowExecutionStatus.RUNNING);
        running = workflowExecutionRepository.saveAndFlush(running);
        ExecutionScope runningRoot = rootScope(running);
        runningRoot.setStatus(ExecutionScopeStatus.PENDING);
        executionScopeRepository.saveAndFlush(runningRoot);
        entityManager.clear();

        var claimed = executionScopeRepository.claimPendingExecutionRoots(
                Instant.now().minus(1, ChronoUnit.MINUTES),
                10
        );

        assertThat(claimed).extracting(ExecutionScope::getId)
                .containsExactly(root.getId());
        assertThat(claimed.get(0).getStatus())
                .isEqualTo(ExecutionScopeStatus.RUNNING);
    }

    @Test
    void claimsOnlyStaleScopesWhosePreviousStateTransitionCommitted() {
        Workflow workflow = saveWorkflow("workflow-stale-scope-recovery");
        WorkflowDefinition definition = saveDefinition(workflow);
        WorkflowExecution execution = execution(
                workflow,
                definition,
                1,
                Instant.parse("2026-06-21T09:00:00Z")
        );
        execution.setStatus(WorkflowExecutionStatus.RUNNING);
        execution = workflowExecutionRepository.saveAndFlush(execution);

        ExecutionScope recoverable = rootScope(execution);
        recoverable.setCurrentStateName("Done");
        executionScopeRepository.saveAndFlush(recoverable);
        StateExecution completedTask =
                stateExecution(recoverable, 1, "CallTool");
        completedTask.setStatus(StateExecutionStatus.SUCCEEDED);
        stateExecutionRepository.saveAndFlush(completedTask);

        ExecutionScope unfinishedTask = new ExecutionScope();
        unfinishedTask.setWorkflowExecution(execution);
        unfinishedTask.setScopeType(ExecutionScopeType.MAP_ITERATION);
        unfinishedTask.setScopePath("root/item-1");
        unfinishedTask.setStatus(ExecutionScopeStatus.RUNNING);
        unfinishedTask.setCurrentStateName("CallTool");
        unfinishedTask.setCurrentStateInput("{}");
        unfinishedTask = executionScopeRepository.saveAndFlush(unfinishedTask);
        StateExecution runningState =
                stateExecution(unfinishedTask, 1, "CallTool");
        runningState.setStatus(StateExecutionStatus.RUNNING);
        stateExecutionRepository.saveAndFlush(runningState);

        ExecutionScope fresh = new ExecutionScope();
        fresh.setWorkflowExecution(execution);
        fresh.setScopeType(ExecutionScopeType.MAP_ITERATION);
        fresh.setScopePath("root/item-2");
        fresh.setStatus(ExecutionScopeStatus.RUNNING);
        fresh.setCurrentStateName("Done");
        fresh.setCurrentStateInput("{}");
        fresh = executionScopeRepository.saveAndFlush(fresh);
        StateExecution freshCompleted =
                stateExecution(fresh, 1, "CallTool");
        freshCompleted.setStatus(StateExecutionStatus.SUCCEEDED);
        stateExecutionRepository.saveAndFlush(freshCompleted);

        Instant staleAt = Instant.now().minus(2, ChronoUnit.MINUTES);
        entityManager.createNativeQuery("""
                UPDATE execution_scopes
                SET updated_at = :staleAt
                WHERE id IN (:recoverableId, :unfinishedId)
                """)
                .setParameter("staleAt", staleAt)
                .setParameter("recoverableId", recoverable.getId())
                .setParameter("unfinishedId", unfinishedTask.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        var claimed = executionScopeRepository.claimStaleRunnableScopes(
                Instant.now().minus(1, ChronoUnit.MINUTES),
                10
        );

        assertThat(claimed).extracting(ExecutionScope::getId)
                .containsExactly(recoverable.getId());
        assertThat(executionScopeRepository.claimStaleRunnableScopes(
                Instant.now().minus(1, ChronoUnit.MINUTES),
                10
        )).isEmpty();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation =
                    org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    void simultaneousNodesClaimStaleRunnableScopeExactlyOnce()
            throws Exception {
        TransactionTemplate transactions =
                new TransactionTemplate(transactionManager);
        UUID scopeId = transactions.execute(status -> {
            Workflow workflow = saveWorkflow(
                    "workflow-stale-scope-concurrent"
            );
            WorkflowDefinition definition = saveDefinition(workflow);
            WorkflowExecution execution = execution(
                    workflow,
                    definition,
                    1,
                    Instant.parse("2026-06-21T09:00:00Z")
            );
            execution.setStatus(WorkflowExecutionStatus.RUNNING);
            execution = workflowExecutionRepository.saveAndFlush(execution);

            ExecutionScope scope = rootScope(execution);
            scope.setCurrentStateName("Done");
            scope = executionScopeRepository.saveAndFlush(scope);
            StateExecution completed =
                    stateExecution(scope, 1, "CallTool");
            completed.setStatus(StateExecutionStatus.SUCCEEDED);
            stateExecutionRepository.saveAndFlush(completed);
            entityManager.createNativeQuery("""
                    UPDATE execution_scopes
                    SET updated_at = :staleAt
                    WHERE id = :scopeId
                    """)
                    .setParameter(
                            "staleAt",
                            Instant.now().minus(2, ChronoUnit.MINUTES)
                    )
                    .setParameter("scopeId", scope.getId())
                    .executeUpdate();
            return scope.getId();
        });

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<UUID> claimed;
        try {
            Future<List<UUID>> first = pool.submit(() -> {
                await(start);
                return executionScopeRepository.claimStaleRunnableScopes(
                                Instant.now().minus(1, ChronoUnit.MINUTES),
                                10
                        ).stream()
                        .map(ExecutionScope::getId)
                        .toList();
            });
            Future<List<UUID>> second = pool.submit(() -> {
                await(start);
                return executionScopeRepository.claimStaleRunnableScopes(
                                Instant.now().minus(1, ChronoUnit.MINUTES),
                                10
                        ).stream()
                        .map(ExecutionScope::getId)
                        .toList();
            });
            start.countDown();
            claimed = java.util.stream.Stream.concat(
                    first.get().stream(),
                    second.get().stream()
            ).toList();
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimed).containsExactly(scopeId);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation =
                    org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    void simultaneousNodesMaterializeOneScheduledOccurrence() throws Exception {
        TransactionTemplate transactions =
                new TransactionTemplate(transactionManager);
        Instant now = Instant.now();
        UUID workflowId = transactions.execute(status -> {
            Workflow workflow = saveWorkflow("workflow-schedule-concurrent");
            workflow.setNextRunAt(now.minusSeconds(1));
            return workflowRepository.saveAndFlush(workflow).getId();
        });

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<UUID> claimed;
        try {
            Future<List<UUID>> first = pool.submit(() ->
                    claimAndAdvanceSchedule(start, transactions, now));
            Future<List<UUID>> second = pool.submit(() ->
                    claimAndAdvanceSchedule(start, transactions, now));
            start.countDown();
            claimed = java.util.stream.Stream.concat(
                    first.get().stream(),
                    second.get().stream()
            ).toList();
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimed).containsExactly(workflowId);
    }

    private List<UUID> claimAndAdvanceSchedule(
            CountDownLatch start,
            TransactionTemplate transactions,
            Instant now
    ) {
        await(start);
        return transactions.execute(status -> {
            List<Workflow> workflows =
                    workflowRepository.claimDueWorkflows(now, 1);
            workflows.forEach(workflow -> {
                workflow.setNextRunAt(now.plus(1, ChronoUnit.DAYS));
                workflowRepository.save(workflow);
            });
            return workflows.stream().map(Workflow::getId).toList();
        });
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void findsOverdueWorkflowAndNestedMachineTimeoutCandidates() {
        Instant now = Instant.now();
        Workflow workflow = saveWorkflow("workflow-deadline-candidates");
        WorkflowDefinition definition = saveDefinition(workflow);
        WorkflowExecution overdue = execution(
                workflow,
                definition,
                1,
                Instant.parse("2026-06-21T09:00:00Z")
        );
        overdue.setStatus(WorkflowExecutionStatus.RUNNING);
        overdue.setDeadlineAt(now.minusSeconds(1));
        overdue = workflowExecutionRepository.saveAndFlush(overdue);

        ExecutionScope root = rootScope(overdue);
        ExecutionScope child = new ExecutionScope();
        child.setWorkflowExecution(overdue);
        child.setParentScope(root);
        child.setScopeType(ExecutionScopeType.PARALLEL_BRANCH);
        child.setScopePath("root/Fork/g1/branch-0");
        child.setOwnerStateName("Fork");
        child.setBranchIndex(0);
        child.setStatus(ExecutionScopeStatus.RUNNING);
        child.setStartedAt(now.minusSeconds(60));
        child.setCurrentStateInput("{}");
        executionScopeRepository.saveAndFlush(child);
        entityManager.clear();

        var executions = workflowExecutionRepository
                .findByDeadlineAtLessThanEqualAndStatusIn(
                        now,
                        List.of(WorkflowExecutionStatus.RUNNING),
                        org.springframework.data.domain.PageRequest.of(0, 10)
                );
        var scopes = executionScopeRepository.findNestedTimeoutCandidates(
                ExecutionScopeType.ROOT,
                now,
                List.of(ExecutionScopeStatus.RUNNING)
        );

        assertThat(executions).extracting(WorkflowExecution::getId)
                .containsExactly(overdue.getId());
        assertThat(scopes).extracting(ExecutionScope::getId)
                .containsExactly(child.getId());
    }

    @Test
    void pagesWorkflowExecutionsByNewestRunFirst() {
        Workflow workflow = saveWorkflow("workflow-execution-page");
        WorkflowDefinition definition = saveDefinition(workflow);
        workflowExecutionRepository.saveAndFlush(execution(
                workflow,
                definition,
                1,
                Instant.parse("2026-06-21T09:00:00Z")
        ));
        workflowExecutionRepository.saveAndFlush(execution(
                workflow,
                definition,
                2,
                Instant.parse("2026-06-21T10:00:00Z")
        ));
        workflowExecutionRepository.saveAndFlush(execution(
                workflow,
                definition,
                3,
                Instant.parse("2026-06-21T11:00:00Z")
        ));
        entityManager.clear();

        var firstPage = workflowExecutionRepository
                .findByWorkflowOrderByRunNumberDesc(
                        workflow,
                        org.springframework.data.domain.PageRequest.of(0, 2)
                );

        assertThat(firstPage.getContent())
                .extracting(WorkflowExecution::getRunNumber)
                .containsExactly(3L, 2L);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
    }

    @Test
    void cancellationPersistsAcrossExecutionRuntimeTree() {
        Workflow workflow = saveWorkflow("workflow-cancellation");
        WorkflowDefinition definition = saveDefinition(workflow);
        WorkflowExecution execution = execution(
                workflow,
                definition,
                1,
                Instant.parse("2026-06-21T09:00:00Z")
        );
        execution.setStatus(WorkflowExecutionStatus.RUNNING);
        execution = workflowExecutionRepository.saveAndFlush(execution);

        ExecutionScope root = rootScope(execution);
        root.setStatus(ExecutionScopeStatus.WAITING);
        root = executionScopeRepository.saveAndFlush(root);

        StateExecution state = stateExecution(
                root,
                1,
                "CallTool"
        );
        state.setStatus(StateExecutionStatus.RETRY_WAIT);
        state = stateExecutionRepository.saveAndFlush(state);

        StateExecutionAttempt attempt = attempt(
                state,
                1,
                StateExecutionAttemptStatus.QUEUED
        );
        stateExecutionAttemptRepository.saveAndFlush(attempt);
        entityManager.clear();

        var response = workflowExecutionCancellationService.cancelExecution(
                workflow.getId(),
                execution.getId()
        );
        entityManager.flush();
        entityManager.clear();

        WorkflowExecution canceledExecution =
                workflowExecutionRepository.findById(execution.getId())
                        .orElseThrow();
        ExecutionScope canceledRoot =
                executionScopeRepository.findById(root.getId()).orElseThrow();
        StateExecution canceledState =
                stateExecutionRepository.findById(state.getId()).orElseThrow();
        StateExecutionAttempt canceledAttempt =
                stateExecutionAttemptRepository.findById(attempt.getId())
                        .orElseThrow();

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.CANCELED);
        assertThat(canceledExecution.getStatus())
                .isEqualTo(WorkflowExecutionStatus.CANCELED);
        assertThat(canceledRoot.getStatus())
                .isEqualTo(ExecutionScopeStatus.CANCELED);
        assertThat(canceledState.getStatus())
                .isEqualTo(StateExecutionStatus.CANCELED);
        assertThat(canceledAttempt.getStatus())
                .isEqualTo(StateExecutionAttemptStatus.CANCELED);
    }

    @Test
    void filtersWorkflowCatalogAndIncrementsOptimisticVersion() {
        Workflow matching = saveWorkflow("workflow-catalog-matching");
        matching.setName("Invoice processing");
        matching.setStatus(WorkflowStatus.ACTIVE);
        workflowRepository.saveAndFlush(matching);

        Workflow other = saveWorkflow("workflow-catalog-other");
        other.setName("Daily cleanup");
        other.setStatus(WorkflowStatus.PAUSED);
        workflowRepository.saveAndFlush(other);
        entityManager.clear();

        var page = workflowRepository.findAll(
                (root, query, builder) -> builder.and(
                        builder.equal(
                                root.get("status"),
                                WorkflowStatus.ACTIVE
                        ),
                        builder.like(
                                builder.lower(root.get("name")),
                                "%invoice%"
                        )
                ),
                org.springframework.data.domain.PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).extracting(Workflow::getId)
                .containsExactly(matching.getId());

        Workflow reloaded = workflowRepository.findById(matching.getId())
                .orElseThrow();
        long version = reloaded.getVersion();
        reloaded.setName("Invoice processing v2");
        workflowRepository.saveAndFlush(reloaded);

        assertThat(reloaded.getVersion()).isEqualTo(version + 1);
    }

    private Workflow saveWorkflow(String idempotencyKey) {
        Workflow workflow = new Workflow();
        workflow.setName("Workflow execution test");
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setTimezone("UTC");
        workflow.setMaxAttempts(3);
        workflow.setIdempotencyKey(idempotencyKey);
        return workflowRepository.saveAndFlush(workflow);
    }

    private WorkflowDefinition saveDefinition(Workflow workflow) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflow(workflow);
        definition.setRevision(1);
        definition.setDefinition("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}
                """.trim());
        definition.setDefinitionHash("a".repeat(64));
        WorkflowDefinition saved =
                workflowDefinitionRepository.saveAndFlush(definition);
        workflow.setActiveDefinition(saved);
        workflowRepository.saveAndFlush(workflow);
        return saved;
    }

    private WorkflowExecution execution(
            Workflow workflow,
            WorkflowDefinition definition,
            long runNumber,
            Instant scheduledFor
    ) {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setWorkflow(workflow);
        execution.setWorkflowDefinition(definition);
        execution.setRunNumber(runNumber);
        execution.setScheduledFor(scheduledFor);
        return execution;
    }

    private ExecutionScope rootScope(WorkflowExecution workflowExecution) {
        ExecutionScope scope = new ExecutionScope();
        scope.setWorkflowExecution(workflowExecution);
        scope.setScopeType(ExecutionScopeType.ROOT);
        scope.setScopePath("root");
        scope.setStatus(ExecutionScopeStatus.RUNNING);
        scope.setCurrentStateName("PollStatus");
        scope.setCurrentStateInput("{}");
        return executionScopeRepository.saveAndFlush(scope);
    }

    private StateExecution stateExecution(
            ExecutionScope scope,
            long sequenceNumber,
            String stateName
    ) {
        StateExecution stateExecution = new StateExecution();
        stateExecution.setExecutionScope(scope);
        stateExecution.setSequenceNumber(sequenceNumber);
        stateExecution.setStateName(stateName);
        stateExecution.setStateType(AslStateType.TASK);
        stateExecution.setInput("{}");
        return stateExecution;
    }

    private StateExecutionAttempt attempt(
            StateExecution stateExecution,
            int attemptNumber,
            StateExecutionAttemptStatus status
    ) {
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setStateExecution(stateExecution);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setStatus(status);
        attempt.setArguments("{\"orderId\":\"order-100\"}");
        return attempt;
    }
}
