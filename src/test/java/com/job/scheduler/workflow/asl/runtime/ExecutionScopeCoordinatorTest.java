package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.repository.ExecutionScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionScopeCoordinatorTest {
    private static final String DEFINITION = """
            {
              "StartAt": "Fork",
              "States": {
                "Fork": {
                  "Type": "Parallel",
                  "Branches": [
                    {"StartAt": "A", "States": {"A": {"Type": "Succeed"}}},
                    {"StartAt": "B", "States": {"B": {"Type": "Succeed"}}}
                  ],
                  "Next": "Loop"
                },
                "Loop": {
                  "Type": "Map",
                  "ItemProcessor": {
                    "StartAt": "P",
                    "States": {"P": {"Type": "Succeed"}}
                  },
                  "End": true
                }
              }
            }
            """;

    @Mock
    private ExecutionScopeRepository executionScopeRepository;

    private ObjectMapper objectMapper;
    private ExecutionScopeCoordinator coordinator;
    private ExecutionScope root;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        coordinator = new ExecutionScopeCoordinator(
                executionScopeRepository,
                new AslDefinitionNavigator(objectMapper),
                objectMapper
        );
        root = rootScope();
        root.setVariables("{\"tenant\":\"acme\"}");
    }

    @Test
    void forksBranchWithGenerationPathIsolatedVariablesAndStartState() {
        when(executionScopeRepository.findByWorkflowExecutionAndScopePath(
                root.getWorkflowExecution(),
                "root/Fork/g5/branch-1"
        )).thenReturn(Optional.empty());
        when(executionScopeRepository.save(any(ExecutionScope.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExecutionScope branch = coordinator.forkBranch(
                root,
                "Fork",
                5,
                1,
                objectMapper.createObjectNode().put("item", "x")
        );

        assertThat(branch.getScopeType())
                .isEqualTo(ExecutionScopeType.PARALLEL_BRANCH);
        assertThat(branch.getParentScope()).isSameAs(root);
        assertThat(branch.getOwnerStateName()).isEqualTo("Fork");
        assertThat(branch.getBranchIndex()).isEqualTo(1);
        assertThat(branch.getScopePath()).isEqualTo("root/Fork/g5/branch-1");
        assertThat(branch.getStatus()).isEqualTo(ExecutionScopeStatus.PENDING);
        assertThat(branch.getCurrentStateName()).isEqualTo("B");
        assertThat(objectMapper.readTree(branch.getVariables())
                .get("tenant").stringValue()).isEqualTo("acme");
        assertThat(objectMapper.readTree(branch.getCurrentStateInput())
                .get("item").stringValue()).isEqualTo("x");
    }

    @Test
    void branchForkIsIdempotentOnReplay() {
        ExecutionScope existing = new ExecutionScope();
        existing.setScopePath("root/Fork/g5/branch-0");
        when(executionScopeRepository.findByWorkflowExecutionAndScopePath(
                root.getWorkflowExecution(),
                "root/Fork/g5/branch-0"
        )).thenReturn(Optional.of(existing));

        ExecutionScope branch = coordinator.forkBranch(
                root,
                "Fork",
                5,
                0,
                objectMapper.createObjectNode()
        );

        assertThat(branch).isSameAs(existing);
        verify(executionScopeRepository, never()).save(any(ExecutionScope.class));
    }

    @Test
    void forksMapIterationFromItemProcessor() {
        when(executionScopeRepository.findByWorkflowExecutionAndScopePath(
                root.getWorkflowExecution(),
                "root/Loop/g3/item-7"
        )).thenReturn(Optional.empty());
        when(executionScopeRepository.save(any(ExecutionScope.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExecutionScope iteration = coordinator.forkIteration(
                root,
                "Loop",
                3,
                7,
                objectMapper.createObjectNode().put("n", 7),
                objectMapper.createObjectNode().put("raw", 7)
        );

        assertThat(iteration.getScopeType())
                .isEqualTo(ExecutionScopeType.MAP_ITERATION);
        assertThat(iteration.getItemIndex()).isEqualTo(7L);
        assertThat(iteration.getScopePath()).isEqualTo("root/Loop/g3/item-7");
        assertThat(iteration.getCurrentStateName()).isEqualTo("P");
        assertThat(objectMapper.readTree(iteration.getItemValue())
                .get("raw").intValue()).isEqualTo(7);
    }

    @Test
    void settleReportsPendingWhileAnyChildIsLive() {
        ExecutionScopeCoordinator.ChildSettlement settlement = coordinator.settle(
                List.of(succeededBranch(0, "{\"r\":0}"), runningBranch(1))
        );

        assertThat(settlement.total()).isEqualTo(2);
        assertThat(settlement.allSettled()).isFalse();
        assertThat(settlement.anyFailed()).isFalse();
    }

    @Test
    void settleCollectsOutputsInIndexOrderWhenAllSucceeded() {
        ExecutionScopeCoordinator.ChildSettlement settlement = coordinator.settle(
                List.of(
                        succeededBranch(10, "{\"r\":10}"),
                        succeededBranch(2, "{\"r\":2}"),
                        succeededBranch(0, "{\"r\":0}")
                )
        );

        assertThat(settlement.allSettled()).isTrue();
        assertThat(settlement.anyFailed()).isFalse();
        assertThat(settlement.orderedOutputs())
                .extracting(node -> node.get("r").intValue())
                .containsExactly(0, 2, 10);
    }

    @Test
    void settleReportsFirstFailureInIndexOrder() {
        ExecutionScope failed = succeededBranch(1, null);
        failed.setStatus(ExecutionScopeStatus.FAILED);
        failed.setError("States.TaskFailed");
        failed.setCause("boom");

        ExecutionScopeCoordinator.ChildSettlement settlement = coordinator.settle(
                List.of(succeededBranch(0, "{\"r\":0}"), failed)
        );

        assertThat(settlement.allSettled()).isTrue();
        assertThat(settlement.anyFailed()).isTrue();
        assertThat(settlement.firstFailedPosition()).isEqualTo(1);
        assertThat(settlement.firstError()).isEqualTo("States.TaskFailed");
        assertThat(settlement.firstCause()).isEqualTo("boom");
        assertThat(settlement.orderedOutputs().get(1)).isNull();
    }

    @Test
    void resumesParentExactlyOnceAfterGenerationSettles() {
        root.setStatus(ExecutionScopeStatus.WAITING);
        ExecutionScope child0 = succeededBranch(0, "{\"r\":0}");
        ExecutionScope child1 = succeededBranch(1, "{\"r\":1}");
        when(executionScopeRepository.findByIdForUpdate(child1.getId()))
                .thenReturn(Optional.of(child1));
        when(executionScopeRepository.findByIdForUpdate(root.getId()))
                .thenReturn(Optional.of(root));
        when(executionScopeRepository
                .findByParentScopeAndScopePathStartingWithOrderByScopePathAsc(
                        root,
                        "root/Fork/g5/"
                ))
                .thenReturn(List.of(child0, child1));
        when(executionScopeRepository.save(any(ExecutionScope.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<UUID> first =
                coordinator.onChildSettled(child1.getId());
        Optional<UUID> second =
                coordinator.onChildSettled(child1.getId());

        assertThat(first).contains(root.getId());
        assertThat(second).isEmpty();
        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.RUNNING);
        assertThat(root.getWakeAt()).isNull();
    }

    @Test
    void failParentCancelsLiveSiblingsAndReadiesParent() {
        root.setStatus(ExecutionScopeStatus.WAITING);
        ExecutionScope failed = succeededBranch(0, null);
        failed.setStatus(ExecutionScopeStatus.FAILED);
        ExecutionScope live = runningBranch(1);
        when(executionScopeRepository.findByIdForUpdate(failed.getId()))
                .thenReturn(Optional.of(failed));
        when(executionScopeRepository.findByIdForUpdate(root.getId()))
                .thenReturn(Optional.of(root));
        when(executionScopeRepository
                .findByParentScopeAndScopePathStartingWithOrderByScopePathAsc(
                        root,
                        "root/Fork/g5/"
                ))
                .thenReturn(List.of(failed, live));
        when(executionScopeRepository.save(any(ExecutionScope.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<UUID> parent =
                coordinator.onChildSettled(failed.getId());

        assertThat(parent).contains(root.getId());
        assertThat(live.getStatus()).isEqualTo(ExecutionScopeStatus.CANCELED);
        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.RUNNING);
    }

    private ExecutionScope succeededBranch(int branchIndex, String output) {
        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setParentScope(root);
        scope.setScopeType(ExecutionScopeType.PARALLEL_BRANCH);
        scope.setBranchIndex(branchIndex);
        scope.setScopePath("root/Fork/g5/branch-" + branchIndex);
        scope.setStatus(ExecutionScopeStatus.SUCCEEDED);
        scope.setOutput(output);
        return scope;
    }

    private ExecutionScope runningBranch(int branchIndex) {
        ExecutionScope scope = succeededBranch(branchIndex, null);
        scope.setStatus(ExecutionScopeStatus.RUNNING);
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

        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(execution);
        scope.setScopeType(ExecutionScopeType.ROOT);
        scope.setScopePath("root");
        scope.setStatus(ExecutionScopeStatus.RUNNING);
        return scope;
    }
}
