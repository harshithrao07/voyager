package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.FunctionInvocation;
import com.job.scheduler.entity.FunctionVersion;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.AslStateType;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.enums.FunctionInvocationStatus;
import com.job.scheduler.enums.FunctionSourceMode;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.FunctionVersionStatus;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.enums.StateExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.FunctionDefinitionRepository;
import com.job.scheduler.repository.FunctionInvocationRepository;
import com.job.scheduler.repository.FunctionVersionRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.WorkflowDefinitionRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(WorkflowExecutionRetentionService.class)
class WorkflowExecutionRetentionServiceIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                    .withInitScript("db/pgvector-init.sql")
                    .withDatabaseName("jobscheduler")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @org.springframework.test.context.DynamicPropertySource
    static void configure(
            org.springframework.test.context.DynamicPropertyRegistry registry
    ) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    private WorkflowExecutionRetentionService retentionService;
    @Autowired
    private WorkflowRepository workflowRepository;
    @Autowired
    private WorkflowDefinitionRepository definitionRepository;
    @Autowired
    private WorkflowExecutionRepository executionRepository;
    @Autowired
    private ExecutionScopeRepository scopeRepository;
    @Autowired
    private StateExecutionRepository stateRepository;
    @Autowired
    private StateExecutionAttemptRepository attemptRepository;
    @Autowired
    private FunctionDefinitionRepository functionRepository;
    @Autowired
    private FunctionVersionRepository functionVersionRepository;
    @Autowired
    private FunctionInvocationRepository invocationRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void deletesOnlyOldTerminalExecutionTreesAndIsIdempotent() {
        Instant cutoff = Instant.parse("2026-06-01T00:00:00Z");
        Workflow workflow = saveWorkflow("retention-tree");
        WorkflowDefinition definition = saveDefinition(workflow);
        WorkflowExecution oldTerminal = saveExecution(
                workflow,
                definition,
                1,
                WorkflowExecutionStatus.SUCCEEDED,
                cutoff.minusSeconds(1)
        );
        ExecutionScope root = saveScope(oldTerminal, null, "root");
        ExecutionScope child = saveScope(
                oldTerminal,
                root,
                "root/Fork/g1/branch-0"
        );
        StateExecution rootState = saveState(root, 1, "Fork");
        StateExecution childState = saveState(child, 1, "Done");
        StateExecutionAttempt attempt = saveAttempt(rootState);
        FunctionInvocation invocation = saveInvocation(oldTerminal.getId());

        WorkflowExecution active = saveExecution(
                workflow,
                definition,
                2,
                WorkflowExecutionStatus.RUNNING,
                cutoff.minusSeconds(60)
        );
        WorkflowExecution recentTerminal = saveExecution(
                workflow,
                definition,
                3,
                WorkflowExecutionStatus.FAILED,
                cutoff.plusSeconds(1)
        );
        entityManager.flush();
        entityManager.clear();

        var result = retentionService.deleteCompletedBefore(cutoff, 10);

        assertThat(result).isEqualTo(
                new WorkflowExecutionRetentionService.RetentionResult(
                        1,
                        2,
                        2,
                        1,
                        1
                )
        );
        assertThat(executionRepository.findById(oldTerminal.getId())).isEmpty();
        assertThat(scopeRepository.findById(root.getId())).isEmpty();
        assertThat(scopeRepository.findById(child.getId())).isEmpty();
        assertThat(stateRepository.findById(rootState.getId())).isEmpty();
        assertThat(stateRepository.findById(childState.getId())).isEmpty();
        assertThat(attemptRepository.findById(attempt.getId())).isEmpty();
        assertThat(invocationRepository.findById(invocation.getId())).isEmpty();
        assertThat(executionRepository.findById(active.getId())).isPresent();
        assertThat(executionRepository.findById(recentTerminal.getId()))
                .isPresent();

        assertThat(retentionService.deleteCompletedBefore(cutoff, 10))
                .isEqualTo(
                        WorkflowExecutionRetentionService.RetentionResult.empty()
                );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousNodesDeleteOneEligibleExecutionExactlyOnce()
            throws Exception {
        Instant cutoff = Instant.parse("2026-06-01T00:00:00Z");
        TransactionTemplate transactions =
                new TransactionTemplate(transactionManager);
        UUID executionId = transactions.execute(status -> {
            Workflow workflow = saveWorkflow("retention-concurrent");
            WorkflowDefinition definition = saveDefinition(workflow);
            return saveExecution(
                    workflow,
                    definition,
                    1,
                    WorkflowExecutionStatus.TIMED_OUT,
                    cutoff.minusSeconds(1)
            ).getId();
        });

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> deleted;
        try {
            Future<Integer> first = pool.submit(() -> {
                await(start);
                return retentionService
                        .deleteCompletedBefore(cutoff, 1)
                        .executions();
            });
            Future<Integer> second = pool.submit(() -> {
                await(start);
                return retentionService
                        .deleteCompletedBefore(cutoff, 1)
                        .executions();
            });
            start.countDown();
            deleted = List.of(first.get(), second.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(deleted).containsExactlyInAnyOrder(0, 1);
        assertThat(executionRepository.findById(executionId)).isEmpty();
    }

    private Workflow saveWorkflow(String idempotencyKey) {
        Workflow workflow = new Workflow();
        workflow.setName("Retention workflow");
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
        definition.setDefinition(
                "{\"StartAt\":\"Done\",\"States\":"
                        + "{\"Done\":{\"Type\":\"Succeed\"}}}"
        );
        definition.setDefinitionHash("r".repeat(64));
        WorkflowDefinition saved = definitionRepository
                .saveAndFlush(definition);
        workflow.setActiveDefinition(saved);
        workflowRepository.saveAndFlush(workflow);
        return saved;
    }

    private WorkflowExecution saveExecution(
            Workflow workflow,
            WorkflowDefinition definition,
            long runNumber,
            WorkflowExecutionStatus status,
            Instant completedAt
    ) {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setWorkflow(workflow);
        execution.setWorkflowDefinition(definition);
        execution.setRunNumber(runNumber);
        execution.setStatus(status);
        execution.setCompletedAt(completedAt);
        return executionRepository.saveAndFlush(execution);
    }

    private ExecutionScope saveScope(
            WorkflowExecution execution,
            ExecutionScope parent,
            String path
    ) {
        ExecutionScope scope = new ExecutionScope();
        scope.setWorkflowExecution(execution);
        scope.setParentScope(parent);
        scope.setScopeType(parent == null
                ? ExecutionScopeType.ROOT
                : ExecutionScopeType.PARALLEL_BRANCH);
        scope.setScopePath(path);
        scope.setStatus(ExecutionScopeStatus.SUCCEEDED);
        scope.setCurrentStateInput("{}");
        return scopeRepository.saveAndFlush(scope);
    }

    private StateExecution saveState(
            ExecutionScope scope,
            long sequence,
            String name
    ) {
        StateExecution state = new StateExecution();
        state.setExecutionScope(scope);
        state.setSequenceNumber(sequence);
        state.setStateName(name);
        state.setStateType(AslStateType.PASS);
        state.setStatus(StateExecutionStatus.SUCCEEDED);
        state.setInput("{}");
        return stateRepository.saveAndFlush(state);
    }

    private StateExecutionAttempt saveAttempt(StateExecution state) {
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setStateExecution(state);
        attempt.setAttemptNumber(1);
        attempt.setStatus(StateExecutionAttemptStatus.SUCCEEDED);
        return attemptRepository.saveAndFlush(attempt);
    }

    private FunctionInvocation saveInvocation(UUID executionId) {
        FunctionDefinition function = new FunctionDefinition();
        function.setName("retention-function-" + UUID.randomUUID());
        function.setStatus(FunctionStatus.ENABLED);
        function = functionRepository.saveAndFlush(function);

        FunctionVersion version = new FunctionVersion();
        version.setFunctionDefinition(function);
        version.setVersion(1);
        version.setSourceMode(FunctionSourceMode.SINGLE_FILE);
        version.setLanguageId(71);
        version.setSourceCode("print('{}')");
        version.setCpuTimeLimitSeconds(2.0);
        version.setWallTimeLimitSeconds(10.0);
        version.setMemoryLimitKb(131072);
        version.setMaxFileSizeKb(1024);
        version.setMaxOutputBytes(4096);
        version.setStatus(FunctionVersionStatus.AVAILABLE);
        version = functionVersionRepository.saveAndFlush(version);

        FunctionInvocation invocation = new FunctionInvocation();
        invocation.setFunctionDefinition(function);
        invocation.setFunctionVersion(version);
        invocation.setWorkflowExecutionId(executionId);
        invocation.setStatus(FunctionInvocationStatus.SUCCEEDED);
        invocation.setInputJson("{}");
        invocation.setStartedAt(Instant.parse("2026-05-01T00:00:00Z"));
        invocation.setCompletedAt(Instant.parse("2026-05-01T00:00:01Z"));
        return invocationRepository.saveAndFlush(invocation);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
