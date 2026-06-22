package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.enums.StateExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 1 foundation: advancing a child (Parallel branch / Map iteration) scope
 * must settle only that scope and leave the owning WorkflowExecution untouched.
 */
@ExtendWith(MockitoExtension.class)
class NestedScopeInterpreterTest {
    private static final String DEFINITION = """
            {
              "StartAt": "Fork",
              "States": {
                "Fork": {
                  "Type": "Parallel",
                  "Branches": [
                    {
                      "StartAt": "A",
                      "States": {
                        "A": {"Type": "Succeed", "Output": "{% $states.input %}"}
                      }
                    },
                    {
                      "StartAt": "Boom",
                      "States": {
                        "Boom": {
                          "Type": "Fail",
                          "Error": "Branch.Broke",
                          "Cause": "kaboom"
                        }
                      }
                    }
                  ],
                  "End": true
                }
              }
            }
            """;

    @Mock
    private ExecutionScopeRepository executionScopeRepository;
    @Mock
    private StateExecutionRepository stateExecutionRepository;
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private StateExecutionAttemptRepository attemptRepository;

    private ObjectMapper objectMapper;
    private WorkflowInterpreter interpreter;
    private ExecutionScope root;
    private AtomicReference<StateExecution> latestState;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AslJsonataEvaluator evaluator =
                new AslJsonataEvaluator(objectMapper, 100, 100);
        AslVariableAssignmentEvaluator assignmentEvaluator =
                new AslVariableAssignmentEvaluator(evaluator, objectMapper);
        AslDefinitionNavigator navigator =
                new AslDefinitionNavigator(objectMapper);
        interpreter = new WorkflowInterpreter(
                executionScopeRepository,
                stateExecutionRepository,
                workflowExecutionRepository,
                attemptRepository,
                navigator,
                objectMapper,
                evaluator,
                assignmentEvaluator,
                new AslRetryResolver(new AslErrorMatcher()),
                new AslCatchResolver(new AslErrorMatcher()),
                new ExecutionScopeCoordinator(
                        executionScopeRepository,
                        navigator,
                        objectMapper
                ),
                WorkflowPayloadLimits.defaults(objectMapper),
                4,
                1000L,
                List.of(
                        new SucceedStateExecutor(evaluator),
                        new FailStateExecutor(evaluator)
                )
        );
        root = rootScope();
        latestState = new AtomicReference<>();

        when(stateExecutionRepository.save(any(StateExecution.class)))
                .thenAnswer(invocation -> {
                    StateExecution stateExecution = invocation.getArgument(0);
                    if (stateExecution.getId() == null) {
                        stateExecution.setId(UUID.randomUUID());
                    }
                    latestState.set(stateExecution);
                    return stateExecution;
                });
        when(executionScopeRepository.save(any(ExecutionScope.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void childBranchSuccessSettlesScopeOnlyAndLeavesExecutionRunning() {
        ExecutionScope branch = branchScope(0, "A", "{\"k\":\"v\"}");
        when(executionScopeRepository.findByIdForUpdate(branch.getId()))
                .thenReturn(Optional.of(branch));
        when(stateExecutionRepository
                .findFirstByExecutionScopeOrderBySequenceNumberDesc(branch))
                .thenAnswer(invocation -> Optional.ofNullable(latestState.get()));

        InterpreterOutcome outcome = interpreter.advance(branch.getId());

        assertThat(outcome).isInstanceOf(InterpreterOutcome.Succeeded.class);
        assertThat(branch.getStatus())
                .isEqualTo(ExecutionScopeStatus.SUCCEEDED);
        assertThat(objectMapper.readTree(branch.getOutput())
                .get("k").stringValue()).isEqualTo("v");
        assertThat(latestState.get().getStatus())
                .isEqualTo(StateExecutionStatus.SUCCEEDED);

        WorkflowExecution execution = branch.getWorkflowExecution();
        assertThat(execution.getStatus())
                .isEqualTo(WorkflowExecutionStatus.RUNNING);
        assertThat(execution.getOutput()).isNull();
        assertThat(execution.getCompletedAt()).isNull();
    }

    @Test
    void childBranchFailureFailsScopeOnlyAndLeavesExecutionRunning() {
        ExecutionScope branch = branchScope(1, "Boom", "{}");
        when(executionScopeRepository.findByIdForUpdate(branch.getId()))
                .thenReturn(Optional.of(branch));
        when(stateExecutionRepository
                .findFirstByExecutionScopeOrderBySequenceNumberDesc(branch))
                .thenAnswer(invocation -> Optional.ofNullable(latestState.get()));

        InterpreterOutcome outcome = interpreter.advance(branch.getId());

        assertThat(outcome).isEqualTo(
                new InterpreterOutcome.Failed("Branch.Broke", "kaboom")
        );
        assertThat(branch.getStatus()).isEqualTo(ExecutionScopeStatus.FAILED);
        assertThat(branch.getError()).isEqualTo("Branch.Broke");

        WorkflowExecution execution = branch.getWorkflowExecution();
        assertThat(execution.getStatus())
                .isEqualTo(WorkflowExecutionStatus.RUNNING);
        assertThat(execution.getError()).isNull();
        assertThat(execution.getCompletedAt()).isNull();
    }

    private ExecutionScope branchScope(
            int branchIndex,
            String startState,
            String input
    ) {
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(root.getWorkflowExecution());
        scope.setParentScope(root);
        scope.setScopeType(ExecutionScopeType.PARALLEL_BRANCH);
        scope.setOwnerStateName("Fork");
        scope.setBranchIndex(branchIndex);
        scope.setScopePath("root/Fork/branch-" + branchIndex);
        scope.setStatus(ExecutionScopeStatus.PENDING);
        scope.setCurrentStateName(startState);
        scope.setCurrentStateInput(input);
        scope.setVariables("{}");
        return scope;
    }

    private ExecutionScope rootScope() {
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID());

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(UUID.randomUUID());
        definition.setWorkflow(workflow);
        definition.setRevision(1);
        definition.setDefinition(DEFINITION);

        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID());
        execution.setWorkflow(workflow);
        execution.setWorkflowDefinition(definition);
        execution.setRunNumber(1);
        execution.setStatus(WorkflowExecutionStatus.RUNNING);
        execution.setStartedAt(Instant.now());
        execution.setInput("{}");

        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(execution);
        scope.setScopeType(ExecutionScopeType.ROOT);
        scope.setScopePath("root");
        scope.setStatus(ExecutionScopeStatus.RUNNING);
        return scope;
    }
}
