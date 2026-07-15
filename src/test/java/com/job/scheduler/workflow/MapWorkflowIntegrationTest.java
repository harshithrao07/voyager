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
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.WorkflowDefinitionRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import com.job.scheduler.scheduler.StaleWorkflowScopeRecoverySchedulerService;
import com.job.scheduler.service.WorkflowExecutionRunner;
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
 * End-to-end Map runtime over a real database: item resolution, ItemSelector,
 * ordered collection, MaxConcurrency windowing, Catch, and Retry. Iterations are
 * inline, so the whole fork/window/join completes within one drive.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MapWorkflowIntegrationTest {

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
    private UUID currentExecutionId;

    @BeforeEach
    void setUp() {
        AslJsonataEvaluator evaluator =
                new AslJsonataEvaluator(objectMapper, 2000, 100);
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
                64,
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
    void mapResolvesItemsAppliesSelectorAndCollectsOrderedOutputs() {
        UUID rootScopeId = persistMap("""
                {
                  "StartAt": "Map",
                  "States": {
                    "Map": {
                      "Type": "Map",
                      "Items": "{% [1, 2, 3] %}",
                      "ItemSelector": {
                        "n": "{% $states.context.Map.Item.Value %}",
                        "i": "{% $states.context.Map.Item.Index %}"
                      },
                      "ItemProcessor": {
                        "StartAt": "P",
                        "States": {
                          "P": {"Type":"Succeed","Output":"{% $states.input.n * 10 %}"}
                        }
                      },
                      "End": true
                    }
                  }
                }
                """);

        WorkflowExecutionResponseDTO response =
                runner.resume(currentExecutionId, rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        JsonNode output = objectMapper.readTree(reload().getOutput());
        assertThat(output.isArray()).isTrue();
        assertThat(output.get(0).intValue()).isEqualTo(10);
        assertThat(output.get(1).intValue()).isEqualTo(20);
        assertThat(output.get(2).intValue()).isEqualTo(30);
    }

    @Test
    void mapItemValueIsAvailableInsideIterationFromDurableScope() {
        // No ItemSelector: the iteration input is the raw item, and the
        // processor state reads $$.Map.Item.Value/.Index directly. This is the
        // path that is reconstructed from the durable item_value/item_index
        // columns rather than from the in-memory fork loop.
        UUID rootScopeId = persistMap("""
                {
                  "StartAt": "Map",
                  "States": {
                    "Map": {
                      "Type": "Map",
                      "Items": "{% [1, 2, 3] %}",
                      "ItemProcessor": {
                        "StartAt": "P",
                        "States": {
                          "P": {
                            "Type": "Succeed",
                            "Output": "{% $states.context.Map.Item.Value * 100 + $states.context.Map.Item.Index %}"
                          }
                        }
                      },
                      "End": true
                    }
                  }
                }
                """);

        WorkflowExecutionResponseDTO response =
                runner.resume(currentExecutionId, rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        JsonNode output = objectMapper.readTree(reload().getOutput());
        assertThat(output.isArray()).isTrue();
        assertThat(output.get(0).intValue()).isEqualTo(100);
        assertThat(output.get(1).intValue()).isEqualTo(201);
        assertThat(output.get(2).intValue()).isEqualTo(302);
    }

    @Test
    void mapWithMaxConcurrencyOneProcessesEveryItemInOrder() {
        UUID rootScopeId = persistMap("""
                {
                  "StartAt": "Map",
                  "States": {
                    "Map": {
                      "Type": "Map",
                      "Items": "{% [1, 2, 3, 4] %}",
                      "MaxConcurrency": 1,
                      "ItemProcessor": {
                        "StartAt": "P",
                        "States": {
                          "P": {"Type":"Succeed","Output":"{% $states.input * 100 %}"}
                        }
                      },
                      "End": true
                    }
                  }
                }
                """);

        WorkflowExecutionResponseDTO response =
                runner.resume(currentExecutionId, rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        JsonNode output = objectMapper.readTree(reload().getOutput());
        assertThat(output).hasSize(4);
        assertThat(output.get(0).intValue()).isEqualTo(100);
        assertThat(output.get(3).intValue()).isEqualTo(400);

        ExecutionScope root = executionScopeRepository
                .findById(rootScopeId).orElseThrow();
        List<ExecutionScope> iterations = executionScopeRepository
                .findByParentScopeOrderByScopePathAsc(root);
        assertThat(iterations).hasSize(4);
        assertThat(iterations)
                .allMatch(iteration -> iteration.getStatus()
                        == ExecutionScopeStatus.SUCCEEDED);
    }

    @Test
    void unhandledIterationFailurePropagatesAndFailsTheMap() {
        UUID rootScopeId = persistMap("""
                {
                  "StartAt": "Map",
                  "States": {
                    "Map": {
                      "Type": "Map",
                      "Items": "{% [1, 2] %}",
                      "ItemProcessor": {
                        "StartAt": "P",
                        "States": {
                          "P": {"Type":"Fail","Error":"Item.Bad","Cause":"nope"}
                        }
                      },
                      "End": true
                    }
                  }
                }
                """);

        WorkflowExecutionResponseDTO response =
                runner.resume(currentExecutionId, rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(response.error()).isEqualTo("Item.Bad");
    }

    @Test
    void mapCatchRecoversFromIterationFailure() {
        UUID rootScopeId = persistMap("""
                {
                  "StartAt": "Map",
                  "States": {
                    "Map": {
                      "Type": "Map",
                      "Items": "{% [1] %}",
                      "ItemProcessor": {
                        "StartAt": "P",
                        "States": {
                          "P": {"Type":"Fail","Error":"Item.Bad"}
                        }
                      },
                      "Catch": [
                        {"ErrorEquals":["Item.Bad"],"Next":"Recovered"}
                      ],
                      "End": true
                    },
                    "Recovered": {"Type":"Succeed","Output":"{% 'recovered' %}"}
                  }
                }
                """);

        WorkflowExecutionResponseDTO response =
                runner.resume(currentExecutionId, rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        assertThat(objectMapper.readTree(reload().getOutput()).stringValue())
                .isEqualTo("recovered");
    }

    @Test
    void mapRetryReRunsThenFailsWhenExhausted() {
        UUID rootScopeId = persistMap("""
                {
                  "StartAt": "Map",
                  "States": {
                    "Map": {
                      "Type": "Map",
                      "Items": "{% [1] %}",
                      "ItemProcessor": {
                        "StartAt": "P",
                        "States": {
                          "P": {"Type":"Fail","Error":"Item.Bad"}
                        }
                      },
                      "Retry": [
                        {"ErrorEquals":["Item.Bad"],"MaxAttempts":1,"IntervalSeconds":0}
                      ],
                      "End": true
                    }
                  }
                }
                """);

        WorkflowExecutionResponseDTO response =
                runner.resume(currentExecutionId, rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(response.error()).isEqualTo("Item.Bad");

        ExecutionScope root = executionScopeRepository
                .findById(rootScopeId).orElseThrow();
        List<ExecutionScope> iterations = executionScopeRepository
                .findByParentScopeOrderByScopePathAsc(root);
        assertThat(iterations).hasSize(2);
    }

    @Test
    void restartRecoversLostMapIterationSettlementAndCompletesJoin() {
        UUID rootScopeId = persistMap("""
                {
                  "StartAt": "Map",
                  "States": {
                    "Map": {
                      "Type": "Map",
                      "Items": "{% [1, 2, 3] %}",
                      "ItemProcessor": {
                        "StartAt": "Done",
                        "States": {
                          "Done": {
                            "Type": "Succeed",
                            "Output": "{% $states.input * 10 %}"
                          }
                        }
                      },
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

        WorkflowExecution recovered = reload();
        assertThat(recovered.getStatus())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        assertThat(objectMapper.readTree(recovered.getOutput()).toString())
                .isEqualTo("[10,20,30]");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation =
                    org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    void simultaneousNodesRespectMapConcurrencyWindowWithoutDuplicateItems()
            throws Exception {
        UUID rootScopeId = persistMap("""
                {
                  "StartAt": "Map",
                  "States": {
                    "Map": {
                      "Type": "Map",
                      "Items": "{% [1, 2, 3] %}",
                      "MaxConcurrency": 1,
                      "ItemProcessor": {
                        "StartAt": "Hold",
                        "States": {
                          "Hold": {"Type":"Wait","Seconds":600,"End":true}
                        }
                      },
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

        ExecutionScope root =
                executionScopeRepository.findById(rootScopeId).orElseThrow();
        List<ExecutionScope> iterations =
                executionScopeRepository.findByParentScopeOrderByScopePathAsc(root);
        assertThat(iterations).hasSize(1);
        assertThat(iterations.get(0).getItemIndex()).isZero();
        assertThat(iterations.get(0).getStatus())
                .isEqualTo(ExecutionScopeStatus.WAITING);
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

    @Test
    void mapRejectsDistributedProcessorModeAtRuntime() {
        UUID rootScopeId = persistMap("""
                {
                  "StartAt": "Map",
                  "States": {
                    "Map": {
                      "Type": "Map",
                      "Items": "{% [1] %}",
                      "ItemProcessor": {
                        "ProcessorConfig": {"Mode": "DISTRIBUTED"},
                        "StartAt": "P",
                        "States": {"P": {"Type":"Succeed"}}
                      },
                      "End": true
                    }
                  }
                }
                """);

        WorkflowExecutionResponseDTO response =
                runner.resume(currentExecutionId, rootScopeId);

        assertThat(response.status())
                .isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(response.error()).isEqualTo("States.Runtime");
    }

    private UUID persistMap(String definitionJson) {
        Workflow workflow = new Workflow();
        workflow.setName("Map runtime test");
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setTimezone("UTC");
        workflow.setMaxAttempts(3);
        workflow.setIdempotencyKey("map-" + UUID.randomUUID());
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
        currentExecutionId = execution.getId();

        ExecutionScope root = new ExecutionScope();
        root.setWorkflowExecution(execution);
        root.setScopeType(ExecutionScopeType.ROOT);
        root.setScopePath("root");
        root.setStatus(ExecutionScopeStatus.PENDING);
        root.setCurrentStateName("Map");
        root.setCurrentStateInput("{}");
        root = executionScopeRepository.saveAndFlush(root);
        return root.getId();
    }

    private WorkflowExecution reload() {
        entityManager.flush();
        entityManager.clear();
        return workflowExecutionRepository.findById(currentExecutionId)
                .orElseThrow();
    }
}
