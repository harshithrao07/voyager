package com.job.scheduler.workflow;

import com.job.scheduler.dto.WorkflowExecutionResponseDTO;
import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.enums.WorkflowPriority;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.WorkflowDefinitionRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import com.job.scheduler.scheduler.StaleWorkflowScopeRecoverySchedulerService;
import com.job.scheduler.scheduler.PendingWorkflowExecutionSchedulerService;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.service.WorkflowSchedulingService;
import com.job.scheduler.workflow.asl.runtime.AslCatchResolver;
import com.job.scheduler.workflow.asl.runtime.AslDefinitionNavigator;
import com.job.scheduler.workflow.asl.runtime.AslErrorMatcher;
import com.job.scheduler.workflow.asl.runtime.AslJsonataEvaluator;
import com.job.scheduler.workflow.asl.runtime.AslRetryResolver;
import com.job.scheduler.workflow.asl.runtime.AslVariableAssignmentEvaluator;
import com.job.scheduler.workflow.asl.runtime.ChoiceStateExecutor;
import com.job.scheduler.workflow.asl.runtime.ExecutionScopeCoordinator;
import com.job.scheduler.workflow.asl.runtime.FailStateExecutor;
import com.job.scheduler.workflow.asl.runtime.InterpreterOutcome;
import com.job.scheduler.workflow.asl.runtime.PassStateExecutor;
import com.job.scheduler.workflow.asl.runtime.SucceedStateExecutor;
import com.job.scheduler.workflow.asl.runtime.TaskStateExecutor;
import com.job.scheduler.workflow.asl.runtime.WaitStateExecutor;
import com.job.scheduler.workflow.asl.runtime.WorkflowInterpreter;
import com.job.scheduler.workflow.asl.runtime.WorkflowPayloadLimits;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
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

/**
 * End-to-end Parallel runtime over a real database: fork, concurrent branches
 * driven by the durable interpreter, and join with ordered {@code $states.result}.
 * Branches here are inline (Pass/Succeed/Fail), so the whole fork/join completes
 * within one drive without Kafka.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ParallelWorkflowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("jobscheduler")
                    .withUsername("postgres")
                    .withPassword("postgres");

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
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowExecutionRunner runner;
    private WorkflowInterpreter interpreter;
    private ExecutionScopeCoordinator coordinator;
    private StaleWorkflowScopeRecoverySchedulerService recoveryScheduler;

    @BeforeEach
    void setUp() {
        AslJsonataEvaluator evaluator =
                new AslJsonataEvaluator(objectMapper, 100, 100);
        AslVariableAssignmentEvaluator assignmentEvaluator =
                new AslVariableAssignmentEvaluator(evaluator, objectMapper);
        AslDefinitionNavigator navigator =
                new AslDefinitionNavigator(objectMapper);
        coordinator = new ExecutionScopeCoordinator(
                executionScopeRepository,
                navigator,
                objectMapper
        );
        interpreter = new WorkflowInterpreter(
                executionScopeRepository,
                stateExecutionRepository,
                workflowExecutionRepository,
                stateExecutionAttemptRepository,
                navigator,
                objectMapper,
                evaluator,
                assignmentEvaluator,
                new AslRetryResolver(new AslErrorMatcher()),
                new AslCatchResolver(new AslErrorMatcher()),
                coordinator,
                WorkflowPayloadLimits.defaults(objectMapper),
                4,
                1000L,
                List.of(
                        new PassStateExecutor(evaluator, assignmentEvaluator),
                        new SucceedStateExecutor(evaluator),
                        new ChoiceStateExecutor(evaluator, assignmentEvaluator),
                        new FailStateExecutor(evaluator),
                        new WaitStateExecutor(evaluator, assignmentEvaluator),
                        new TaskStateExecutor(evaluator)
                )
        );
        runner = new WorkflowExecutionRunner(
                null,
                interpreter,
                coordinator,
                objectMapper,
                10000
        );
        recoveryScheduler = new StaleWorkflowScopeRecoverySchedulerService(
                executionScopeRepository,
                runner,
                coordinator
        );
        ReflectionTestUtils.setField(recoveryScheduler, "claimLimit", 100);
        ReflectionTestUtils.setField(recoveryScheduler, "staleTimeoutMs", 1L);
    }

    @Test
    void forkJoinCollectsBranchOutputsInDeclarationOrder() {
        UUID rootScopeId = persistParallel("""
                {
                  "StartAt": "Fork",
                  "States": {
                    "Fork": {
                      "Type": "Parallel",
                      "Branches": [
                        {"StartAt":"A","States":{"A":{"Type":"Succeed","Output":"{% 'a' %}"}}},
                        {"StartAt":"B","States":{"B":{"Type":"Succeed","Output":"{% 'b' %}"}}}
                      ],
                      "End": true
                    }
                  }
                }
                """);
        WorkflowExecution execution = currentExecution();

        WorkflowExecutionResponseDTO response =
                runner.resume(execution.getId(), rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        WorkflowExecution reloaded = reload(execution.getId());
        assertThat(reloaded.getStatus())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        JsonNode output = objectMapper.readTree(reloaded.getOutput());
        assertThat(output.isArray()).isTrue();
        assertThat(output.get(0).stringValue()).isEqualTo("a");
        assertThat(output.get(1).stringValue()).isEqualTo("b");

        ExecutionScope root = reloadScope(rootScopeId);
        List<ExecutionScope> branches = executionScopeRepository
                .findByParentScopeAndScopePathStartingWithOrderByScopePathAsc(
                        root,
                        "root/Fork/g1/"
                );
        assertThat(branches).hasSize(2);
        assertThat(branches)
                .allMatch(branch -> branch.getStatus()
                        == ExecutionScopeStatus.SUCCEEDED);
    }

    @Test
    void unhandledBranchFailureFailsParallelAndCancelsSiblings() {
        UUID rootScopeId = persistParallel("""
                {
                  "StartAt": "Fork",
                  "States": {
                    "Fork": {
                      "Type": "Parallel",
                      "Branches": [
                        {"StartAt":"Boom","States":{"Boom":{"Type":"Fail","Error":"Boom","Cause":"bad"}}},
                        {"StartAt":"Slow","States":{"Slow":{"Type":"Wait","Seconds":600,"End":true}}}
                      ],
                      "End": true
                    }
                  }
                }
                """);
        WorkflowExecution execution = currentExecution();

        WorkflowExecutionResponseDTO response =
                runner.resume(execution.getId(), rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(response.error()).isEqualTo("States.BranchFailed");

        ExecutionScope root = reloadScope(rootScopeId);
        List<ExecutionScope> branches = executionScopeRepository
                .findByParentScopeAndScopePathStartingWithOrderByScopePathAsc(
                        root,
                        "root/Fork/g1/"
                );
        assertThat(branches).hasSize(2);
        assertThat(branches.get(0).getStatus())
                .isEqualTo(ExecutionScopeStatus.FAILED);
        assertThat(branches.get(1).getStatus())
                .isEqualTo(ExecutionScopeStatus.CANCELED);
    }

    @Test
    void parallelCatchRecoversFromBranchFailure() {
        UUID rootScopeId = persistParallel("""
                {
                  "StartAt": "Fork",
                  "States": {
                    "Fork": {
                      "Type": "Parallel",
                      "Branches": [
                        {"StartAt":"Boom","States":{"Boom":{"Type":"Fail","Error":"Boom"}}}
                      ],
                      "Catch": [
                        {"ErrorEquals":["States.BranchFailed"],"Next":"Recovered"}
                      ],
                      "End": true
                    },
                    "Recovered": {"Type":"Succeed","Output":"{% 'recovered' %}"}
                  }
                }
                """);
        WorkflowExecution execution = currentExecution();

        WorkflowExecutionResponseDTO response =
                runner.resume(execution.getId(), rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        WorkflowExecution reloaded = reload(execution.getId());
        assertThat(objectMapper.readTree(reloaded.getOutput()).stringValue())
                .isEqualTo("recovered");
    }

    @Test
    void parallelRetryReForksThenFailsWhenExhausted() {
        UUID rootScopeId = persistParallel("""
                {
                  "StartAt": "Fork",
                  "States": {
                    "Fork": {
                      "Type": "Parallel",
                      "Branches": [
                        {"StartAt":"Boom","States":{"Boom":{"Type":"Fail","Error":"Boom"}}}
                      ],
                      "Retry": [
                        {"ErrorEquals":["States.BranchFailed"],"MaxAttempts":1,"IntervalSeconds":0}
                      ],
                      "End": true
                    }
                  }
                }
                """);
        WorkflowExecution execution = currentExecution();

        WorkflowExecutionResponseDTO response =
                runner.resume(execution.getId(), rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(response.error()).isEqualTo("States.BranchFailed");

        ExecutionScope root = reloadScope(rootScopeId);
        List<ExecutionScope> allBranches =
                executionScopeRepository.findByParentScopeOrderByScopePathAsc(root);
        assertThat(allBranches).hasSize(2);
        assertThat(allBranches)
                .allMatch(branch -> branch.getStatus()
                        == ExecutionScopeStatus.FAILED);
    }

    @Test
    void restartRecoversLostParallelChildSettlementAndCompletesJoin() {
        UUID rootScopeId = persistParallel("""
                {
                  "StartAt": "Fork",
                  "States": {
                    "Fork": {
                      "Type": "Parallel",
                      "Branches": [
                        {"StartAt":"A","States":{"A":{"Type":"Succeed","Output":"{% 'a' %}"}}},
                        {"StartAt":"B","States":{"B":{"Type":"Succeed","Output":"{% 'b' %}"}}}
                      ],
                      "End": true
                    }
                  }
                }
                """);

        InterpreterOutcome.Forked forked =
                (InterpreterOutcome.Forked) interpreter.advance(rootScopeId);
        for (UUID childScopeId : forked.childScopeIds()) {
            assertThat(interpreter.advance(childScopeId))
                    .isInstanceOf(InterpreterOutcome.Succeeded.class);
        }
        makeScopesStale(forked.childScopeIds());
        entityManager.clear();
        setUp();

        recoveryScheduler.recoverStaleRunnableScopes();

        WorkflowExecution recovered = reload(currentExecutionId);
        assertThat(recovered.getStatus())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        assertThat(objectMapper.readTree(recovered.getOutput()).toString())
                .isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void restartRecoversTaskCompletionCommittedBeforeNextStateWasDriven() {
        UUID rootScopeId = persistParallel("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "scheduler://test",
                      "Next": "Done"
                    },
                    "Done": {
                      "Type": "Succeed",
                      "Output": "{% $states.input %}"
                    }
                  }
                }
                """);
        WorkflowExecutionResponseDTO dispatched =
                runner.resume(currentExecutionId, rootScopeId);
        StateExecutionAttempt attempt = stateExecutionAttemptRepository
                .findById(dispatched.stateExecutionAttemptId())
                .orElseThrow();
        attempt.setStatus(StateExecutionAttemptStatus.RUNNING);
        attempt.setStartedAt(Instant.now());
        stateExecutionAttemptRepository.saveAndFlush(attempt);

        interpreter.completeTaskSuccess(
                attempt.getId(),
                objectMapper.createObjectNode().put("ok", true)
        );
        makeScopesStale(List.of(rootScopeId));
        entityManager.clear();
        setUp();

        recoveryScheduler.recoverStaleRunnableScopes();

        WorkflowExecution recovered = reload(currentExecutionId);
        assertThat(recovered.getStatus())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        assertThat(objectMapper.readTree(recovered.getOutput()).get("ok")
                .booleanValue()).isTrue();
        assertThat(stateExecutionRepository
                .findByExecutionScopeOrderBySequenceNumberAsc(
                        reloadScope(rootScopeId)
                )).hasSize(2);
    }

    @Test
    void restartRecoversScheduledExecutionClaimedBeforeInterpreterStarted() {
        UUID rootScopeId = persistParallel("""
                {
                  "StartAt": "Done",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);
        ExecutionScope root = reloadScope(rootScopeId);
        root.setStatus(ExecutionScopeStatus.RUNNING);
        executionScopeRepository.saveAndFlush(root);
        makeScopesStale(List.of(rootScopeId));
        entityManager.clear();
        setUp();

        PendingWorkflowExecutionSchedulerService pendingScheduler =
                new PendingWorkflowExecutionSchedulerService(
                        executionScopeRepository,
                        runner,
                        org.mockito.Mockito.mock(
                                WorkflowSchedulingService.class
                        )
                );
        ReflectionTestUtils.setField(pendingScheduler, "claimLimit", 10);
        ReflectionTestUtils.setField(pendingScheduler, "claimTimeoutMs", 1L);
        pendingScheduler.startPendingExecutions();

        assertThat(reload(currentExecutionId).getStatus())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation =
                    org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    void simultaneousNodesForkAndJoinParallelExactlyOnce() throws Exception {
        UUID rootScopeId = persistParallel("""
                {
                  "StartAt": "Fork",
                  "States": {
                    "Fork": {
                      "Type": "Parallel",
                      "Branches": [
                        {"StartAt":"A","States":{"A":{"Type":"Succeed","Output":"{% 'a' %}"}}},
                        {"StartAt":"B","States":{"B":{"Type":"Succeed","Output":"{% 'b' %}"}}}
                      ],
                      "End": true
                    }
                  }
                }
                """);
        UUID executionId = currentExecutionId;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        TransactionTemplate transactions =
                new TransactionTemplate(transactionManager);
        try {
            Future<?> first = pool.submit(() -> {
                await(start);
                transactions.executeWithoutResult(status ->
                        runner.resume(executionId, rootScopeId));
            });
            Future<?> second = pool.submit(() -> {
                await(start);
                transactions.executeWithoutResult(status ->
                        runner.resume(executionId, rootScopeId));
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }

        WorkflowExecution execution =
                workflowExecutionRepository.findById(executionId).orElseThrow();
        ExecutionScope root =
                executionScopeRepository.findById(rootScopeId).orElseThrow();
        assertThat(execution.getStatus())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        assertThat(executionScopeRepository
                .findByParentScopeOrderByScopePathAsc(root))
                .hasSize(2)
                .extracting(ExecutionScope::getScopePath)
                .doesNotHaveDuplicates();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void makeScopesStale(List<UUID> scopeIds) {
        entityManager.createNativeQuery("""
                UPDATE execution_scopes
                SET updated_at = :staleAt
                WHERE id IN (:scopeIds)
                """)
                .setParameter(
                        "staleAt",
                        Instant.now().minus(2, ChronoUnit.MINUTES)
                )
                .setParameter("scopeIds", scopeIds)
                .executeUpdate();
        entityManager.flush();
    }

    private UUID persistParallel(String definitionJson) {
        Workflow workflow = new Workflow();
        workflow.setName("Parallel runtime test");
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setPriority(WorkflowPriority.MEDIUM);
        workflow.setTimezone("UTC");
        workflow.setMaxAttempts(3);
        workflow.setIdempotencyKey("parallel-" + UUID.randomUUID());
        workflow = workflowRepository.saveAndFlush(workflow);

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflow(workflow);
        definition.setRevision(1);
        definition.setDefinition(definitionJson.trim());
        definition.setDefinitionHash(
                UUID.randomUUID().toString().replace("-", "").repeat(2)
        );
        definition = workflowDefinitionRepository.saveAndFlush(definition);
        workflow.setActiveDefinition(definition);
        workflowRepository.saveAndFlush(workflow);

        WorkflowExecution execution = new WorkflowExecution();
        execution.setWorkflow(workflow);
        execution.setWorkflowDefinition(definition);
        execution.setRunNumber(1);
        execution.setScheduledFor(Instant.parse("2026-06-21T06:00:00Z"));
        execution.setStatus(WorkflowExecutionStatus.PENDING);
        execution.setInput("{}");
        execution = workflowExecutionRepository.saveAndFlush(execution);

        ExecutionScope root = new ExecutionScope();
        root.setWorkflowExecution(execution);
        root.setScopeType(ExecutionScopeType.ROOT);
        root.setScopePath("root");
        root.setStatus(ExecutionScopeStatus.PENDING);
        try {
            root.setCurrentStateName(
                    objectMapper.readTree(definitionJson)
                            .get("StartAt")
                            .stringValue()
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Invalid test workflow definition",
                    exception
            );
        }
        root.setCurrentStateInput("{}");
        root = executionScopeRepository.saveAndFlush(root);

        this.currentExecutionId = execution.getId();
        return root.getId();
    }

    private UUID currentExecutionId;

    private WorkflowExecution currentExecution() {
        return reload(currentExecutionId);
    }

    private WorkflowExecution reload(UUID executionId) {
        entityManager.flush();
        entityManager.clear();
        return workflowExecutionRepository.findById(executionId).orElseThrow();
    }

    private ExecutionScope reloadScope(UUID scopeId) {
        return executionScopeRepository.findById(scopeId).orElseThrow();
    }
}
