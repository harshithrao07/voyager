package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
import com.job.scheduler.enums.AslStateType;
import com.job.scheduler.enums.StateExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.repository.ExecutionScopeRepository;
import com.job.scheduler.repository.StateExecutionRepository;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WorkflowInterpreterTest {
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
    private AtomicReference<StateExecutionAttempt> latestAttempt;
    private List<StateExecutionAttempt> attempts;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AslJsonataEvaluator evaluator =
                new AslJsonataEvaluator(objectMapper, 100, 100);
        AslVariableAssignmentEvaluator assignmentEvaluator =
                new AslVariableAssignmentEvaluator(evaluator, objectMapper);
        AslDefinitionNavigator navigator =
                new AslDefinitionNavigator(objectMapper);
        interpreter = newInterpreter(
                evaluator,
                assignmentEvaluator,
                navigator,
                WorkflowPayloadLimits.defaults(objectMapper)
        );
        root = rootScope();
        latestState = new AtomicReference<>();
        latestAttempt = new AtomicReference<>();
        attempts = new ArrayList<>();

        lenient().when(executionScopeRepository.findByIdForUpdate(root.getId()))
                .thenReturn(Optional.of(root));
        lenient().when(stateExecutionRepository
                .findFirstByExecutionScopeOrderBySequenceNumberDesc(root))
                .thenAnswer(invocation ->
                        Optional.ofNullable(latestState.get()));
        lenient().when(stateExecutionRepository.save(any(StateExecution.class)))
                .thenAnswer(invocation -> {
                    StateExecution stateExecution = invocation.getArgument(0);
                    if (stateExecution.getId() == null) {
                        stateExecution.setId(UUID.randomUUID());
                    }
                    latestState.set(stateExecution);
                    return stateExecution;
                });
        lenient().when(executionScopeRepository.save(any(ExecutionScope.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(workflowExecutionRepository.save(any(WorkflowExecution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(attemptRepository.save(any(StateExecutionAttempt.class)))
                .thenAnswer(invocation -> {
                    StateExecutionAttempt attempt = invocation.getArgument(0);
                    if (attempt.getId() == null) {
                        attempt.setId(UUID.randomUUID());
                    }
                    if (!attempts.contains(attempt)) {
                        attempts.add(attempt);
                    }
                    latestAttempt.set(attempt);
                    return attempt;
                });
    }

    private WorkflowInterpreter newInterpreter(
            AslJsonataEvaluator evaluator,
            AslVariableAssignmentEvaluator assignmentEvaluator,
            AslDefinitionNavigator navigator,
            WorkflowPayloadLimits payloadLimits
    ) {
        return new WorkflowInterpreter(
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
                payloadLimits,
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
    }

    @Test
    void executesPassThenSucceedAndPersistsFinalOutput() {
        InterpreterOutcome first = interpreter.advance(root.getId());

        assertThat(first).isInstanceOf(InterpreterOutcome.Continued.class);
        assertThat(root.getCurrentStateName()).isEqualTo("Done");
        assertThat(objectMapper.readTree(root.getCurrentStateInput())
                .get("message").stringValue()).isEqualTo("hi Ada");
        assertThat(objectMapper.readTree(root.getVariables())
                .get("greeting").stringValue()).isEqualTo("hello");
        assertThat(latestState.get().getSequenceNumber()).isEqualTo(1);

        InterpreterOutcome second = interpreter.advance(root.getId());

        assertThat(second).isInstanceOf(InterpreterOutcome.Succeeded.class);
        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.SUCCEEDED);
        assertThat(root.getCurrentStateName()).isNull();
        assertThat(root.getWorkflowExecution().getStatus())
                .isEqualTo(WorkflowExecutionStatus.SUCCEEDED);
        assertThat(objectMapper.readTree(root.getWorkflowExecution().getOutput())
                .get("message").stringValue()).isEqualTo("hi Ada");
        assertThat(latestState.get().getSequenceNumber()).isEqualTo(2);
    }

    @Test
    void persistsExplicitFailAcrossStateScopeAndWorkflowExecution() {
        root.getWorkflowExecution().getWorkflowDefinition().setDefinition("""
                {
                  "StartAt": "Reject",
                  "States": {
                    "Reject": {
                      "Type": "Fail",
                      "Error": "{% $states.input.error %}",
                      "Cause": "Order rejected"
                    }
                  }
                }
                """);
        root.setCurrentStateName("Reject");
        root.setCurrentStateInput("{\"error\":\"Order.Invalid\"}");

        InterpreterOutcome outcome = interpreter.advance(root.getId());

        assertThat(outcome).isEqualTo(
                new InterpreterOutcome.Failed(
                        "Order.Invalid",
                        "Order rejected"
                )
        );
        assertThat(latestState.get().getStatus())
                .isEqualTo(com.job.scheduler.enums.StateExecutionStatus.FAILED);
        assertThat(latestState.get().getError()).isEqualTo("Order.Invalid");
        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.FAILED);
        assertThat(root.getWorkflowExecution().getStatus())
                .isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(root.getWorkflowExecution().getCause())
                .isEqualTo("Order rejected");
    }

    @Test
    void persistsWaitThenCompletesSameStateAndContinuesAfterWake() {
        root.getWorkflowExecution().getWorkflowDefinition().setDefinition("""
                {
                  "StartAt": "Pause",
                  "States": {
                    "Pause": {
                      "Type": "Wait",
                      "Seconds": 60,
                      "Next": "Done"
                    },
                    "Done": {
                      "Type": "Succeed"
                    }
                  }
                }
                """);
        root.setCurrentStateName("Pause");
        root.setCurrentStateInput("{\"orderId\":\"100\"}");

        InterpreterOutcome waiting = interpreter.advance(root.getId());

        assertThat(waiting).isInstanceOf(InterpreterOutcome.Waiting.class);
        StateExecution waitExecution = latestState.get();
        assertThat(waitExecution.getStatus())
                .isEqualTo(com.job.scheduler.enums.StateExecutionStatus.WAITING);
        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.WAITING);
        assertThat(root.getCurrentStateName()).isEqualTo("Done");
        assertThat(root.getWakeAt()).isNotNull();
        assertThat(root.getWorkflowExecution().getStatus())
                .isEqualTo(WorkflowExecutionStatus.WAITING);

        InterpreterOutcome stillWaiting = interpreter.advance(root.getId());
        assertThat(stillWaiting).isInstanceOf(InterpreterOutcome.Waiting.class);
        assertThat(latestState.get()).isSameAs(waitExecution);

        root.setWakeAt(Instant.now().minusSeconds(1));
        InterpreterOutcome succeeded = interpreter.advance(root.getId());

        assertThat(succeeded).isInstanceOf(InterpreterOutcome.Succeeded.class);
        assertThat(waitExecution.getStatus())
                .isEqualTo(com.job.scheduler.enums.StateExecutionStatus.SUCCEEDED);
        assertThat(latestState.get().getSequenceNumber()).isEqualTo(2);
        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.SUCCEEDED);
    }

    @Test
    void dispatchesTaskOnceAndAppliesResultBeforeContinuing() {
        root.getWorkflowExecution().getWorkflowDefinition().setDefinition("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "scheduler://webhook",
                      "Arguments": {
                        "url": "{% $states.input.url %}",
                        "body": {
                          "id": "{% $states.input.id %}"
                        }
                      },
                      "TimeoutSeconds": "{% 45 %}",
                      "HeartbeatSeconds": 15,
                      "Assign": {
                        "statusCode": "{% $states.result.statusCode %}"
                      },
                      "Output": {
                        "ok": "{% $states.result.statusCode = 200 %}"
                      },
                      "Next": "Done"
                    },
                    "Done": {
                      "Type": "Succeed"
                    }
                  }
                }
                """);
        root.setCurrentStateName("Call");
        root.setCurrentStateInput(
                "{\"url\":\"https://example.com\",\"id\":\"100\"}"
        );

        InterpreterOutcome dispatched = interpreter.advance(root.getId());

        assertThat(dispatched)
                .isInstanceOf(InterpreterOutcome.Dispatched.class);
        StateExecution taskState = latestState.get();
        StateExecutionAttempt attempt = latestAttempt.get();
        assertThat(taskState.getResource())
                .isEqualTo("scheduler://webhook");
        assertThat(attempt.getStatus())
                .isEqualTo(StateExecutionAttemptStatus.PENDING);
        assertThat(objectMapper.readTree(attempt.getArguments())
                .get("body").get("id").stringValue()).isEqualTo("100");
        assertThat(attempt.getTimeoutSeconds()).isEqualTo(45L);
        assertThat(attempt.getHeartbeatSeconds()).isEqualTo(15L);

        when(attemptRepository
                .findFirstByStateExecutionOrderByAttemptNumberDesc(taskState))
                .thenReturn(Optional.of(attempt));
        InterpreterOutcome duplicateAdvance = interpreter.advance(root.getId());
        assertThat(duplicateAdvance).isEqualTo(
                new InterpreterOutcome.Dispatched(attempt.getId())
        );

        attempt.setStatus(StateExecutionAttemptStatus.RUNNING);
        attempt.setStartedAt(Instant.now());
        when(attemptRepository.findByIdForUpdate(attempt.getId()))
                .thenReturn(Optional.of(attempt));
        InterpreterOutcome completed = interpreter.completeTaskSuccess(
                attempt.getId(),
                objectMapper.createObjectNode().put("statusCode", 200)
        );

        assertThat(completed)
                .isInstanceOf(InterpreterOutcome.Continued.class);
        assertThat(taskState.getStatus())
                .isEqualTo(com.job.scheduler.enums.StateExecutionStatus.SUCCEEDED);
        assertThat(root.getCurrentStateName()).isEqualTo("Done");
        assertThat(objectMapper.readTree(root.getCurrentStateInput())
                .get("ok").booleanValue()).isTrue();
        assertThat(objectMapper.readTree(root.getVariables())
                .get("statusCode").intValue()).isEqualTo(200);

        InterpreterOutcome duplicateCompletion =
                interpreter.completeTaskSuccess(
                        attempt.getId(),
                        objectMapper.createObjectNode().put("statusCode", 500)
                );

        assertThat(duplicateCompletion).isEqualTo(
                new InterpreterOutcome.Continued(
                        "Done",
                        objectMapper.createObjectNode().put("ok", true)
                )
        );
        assertThat(objectMapper.readTree(root.getCurrentStateInput())
                .get("ok").booleanValue()).isTrue();
    }

    @Test
    void schedulesRetryAsAnotherAttemptOnTheSameStateExecution() {
        configureTaskDefinition("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "scheduler://cleanup",
                      "Retry": [{
                        "ErrorEquals": ["Temporary"],
                        "IntervalSeconds": 2,
                        "MaxAttempts": 2
                      }],
                      "Next": "Done"
                    },
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);

        interpreter.advance(root.getId());
        StateExecution stateExecution = latestState.get();
        StateExecutionAttempt firstAttempt = latestAttempt.get();
        firstAttempt.setStatus(StateExecutionAttemptStatus.RUNNING);
        firstAttempt.setStartedAt(Instant.now());
        when(attemptRepository.findByIdForUpdate(firstAttempt.getId()))
                .thenReturn(Optional.of(firstAttempt));
        when(attemptRepository
                .findByStateExecutionOrderByAttemptNumberAsc(stateExecution))
                .thenReturn(attempts);

        InterpreterOutcome outcome = interpreter.completeTaskFailure(
                firstAttempt.getId(),
                "Temporary",
                "try later"
        );

        assertThat(outcome)
                .isInstanceOf(InterpreterOutcome.RetryScheduled.class);
        StateExecutionAttempt secondAttempt = latestAttempt.get();
        assertThat(secondAttempt.getStateExecution()).isSameAs(stateExecution);
        assertThat(secondAttempt.getAttemptNumber()).isEqualTo(2);
        assertThat(secondAttempt.getArguments())
                .isEqualTo(firstAttempt.getArguments());
        assertThat(stateExecution.getStatus())
                .isEqualTo(com.job.scheduler.enums.StateExecutionStatus.RETRY_WAIT);
        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.RETRY_WAIT);
        assertThat(root.getCurrentStateName()).isEqualTo("Call");

        when(attemptRepository
                .findFirstByStateExecutionOrderByAttemptNumberDesc(
                        stateExecution
                ))
                .thenReturn(Optional.of(secondAttempt));
        InterpreterOutcome duplicateFailure =
                interpreter.completeTaskFailure(
                        firstAttempt.getId(),
                        "Temporary",
                        "duplicate delivery"
                );

        assertThat(duplicateFailure).isEqualTo(
                new InterpreterOutcome.RetryScheduled(
                        secondAttempt.getId(),
                        secondAttempt.getAvailableAt()
                )
        );
        assertThat(attempts).hasSize(2);
    }

    @Test
    void appliesFirstMatchingCatcherAfterRetriesAreExhausted() {
        configureTaskDefinition("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "scheduler://cleanup",
                      "Retry": [{
                        "ErrorEquals": ["Temporary"],
                        "MaxAttempts": 0
                      }],
                      "Catch": [{
                        "ErrorEquals": ["Temporary"],
                        "Assign": {
                          "lastError": "{% $states.errorOutput.Error %}"
                        },
                        "Output": {
                          "handled": true,
                          "reason": "{% $states.errorOutput.Cause %}"
                        },
                        "Next": "Recovered"
                      }],
                      "Next": "Done"
                    },
                    "Recovered": {"Type": "Succeed"},
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);

        interpreter.advance(root.getId());
        StateExecution stateExecution = latestState.get();
        StateExecutionAttempt attempt = latestAttempt.get();
        attempt.setStatus(StateExecutionAttemptStatus.RUNNING);
        when(attemptRepository.findByIdForUpdate(attempt.getId()))
                .thenReturn(Optional.of(attempt));
        when(attemptRepository
                .findByStateExecutionOrderByAttemptNumberAsc(stateExecution))
                .thenReturn(attempts);

        InterpreterOutcome outcome = interpreter.completeTaskFailure(
                attempt.getId(),
                "Temporary",
                "dependency unavailable"
        );

        assertThat(outcome)
                .isInstanceOf(InterpreterOutcome.Continued.class);
        assertThat(root.getCurrentStateName()).isEqualTo("Recovered");
        assertThat(objectMapper.readTree(root.getCurrentStateInput())
                .get("handled").booleanValue()).isTrue();
        assertThat(objectMapper.readTree(root.getCurrentStateInput())
                .get("reason").stringValue())
                .isEqualTo("dependency unavailable");
        assertThat(objectMapper.readTree(root.getVariables())
                .get("lastError").stringValue()).isEqualTo("Temporary");
        assertThat(stateExecution.getStatus())
                .isEqualTo(com.job.scheduler.enums.StateExecutionStatus.SUCCEEDED);
        assertThat(root.getWorkflowExecution().getStatus())
                .isEqualTo(WorkflowExecutionStatus.RUNNING);

        InterpreterOutcome duplicateFailure =
                interpreter.completeTaskFailure(
                        attempt.getId(),
                        "Temporary",
                        "duplicate delivery"
                );

        assertThat(duplicateFailure).isEqualTo(
                new InterpreterOutcome.Continued(
                        "Recovered",
                        objectMapper.readTree(root.getCurrentStateInput())
                )
        );
        assertThat(root.getCurrentStateName()).isEqualTo("Recovered");
    }

    @Test
    void duplicateFailureAfterCancellationReturnsPersistedTerminalOutcome() {
        configureTaskDefinition("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "scheduler://cleanup",
                      "Next": "Done"
                    },
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);

        interpreter.advance(root.getId());
        StateExecutionAttempt attempt = latestAttempt.get();
        attempt.setStatus(StateExecutionAttemptStatus.CANCELED);
        attempt.setError("Execution.Canceled");
        attempt.setCause("Canceled by user");
        root.setStatus(ExecutionScopeStatus.CANCELED);
        root.setError("Execution.Canceled");
        root.setCause("Canceled by user");
        root.getWorkflowExecution().setStatus(
                WorkflowExecutionStatus.CANCELED
        );
        when(attemptRepository.findByIdForUpdate(attempt.getId()))
                .thenReturn(Optional.of(attempt));

        InterpreterOutcome outcome = interpreter.completeTaskFailure(
                attempt.getId(),
                "LateFailure",
                "worker completed after cancellation"
        );

        assertThat(outcome).isEqualTo(new InterpreterOutcome.Failed(
                "Execution.Canceled",
                "Canceled by user"
        ));
        assertThat(attempt.getError()).isEqualTo("Execution.Canceled");
        assertThat(root.getStatus()).isEqualTo(ExecutionScopeStatus.CANCELED);
    }

    @Test
    void ignoresLateTaskSuccessAfterMachineTimeout() {
        configureTaskDefinition("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "scheduler://cleanup",
                      "Next": "Done"
                    },
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);

        interpreter.advance(root.getId());
        StateExecutionAttempt attempt = latestAttempt.get();
        attempt.setStatus(StateExecutionAttemptStatus.TIMED_OUT);
        attempt.setError("States.Timeout");
        attempt.setCause("Workflow exceeded its ASL TimeoutSeconds");
        root.setStatus(ExecutionScopeStatus.TIMED_OUT);
        root.setError("States.Timeout");
        root.setCause("Workflow exceeded its ASL TimeoutSeconds");
        root.getWorkflowExecution().setStatus(
                WorkflowExecutionStatus.TIMED_OUT
        );
        when(attemptRepository.findByIdForUpdate(attempt.getId()))
                .thenReturn(Optional.of(attempt));

        InterpreterOutcome outcome = interpreter.completeTaskSuccess(
                attempt.getId(),
                objectMapper.createObjectNode().put("late", true)
        );

        assertThat(outcome).isEqualTo(new InterpreterOutcome.Failed(
                "States.Timeout",
                "Workflow exceeded its ASL TimeoutSeconds"
        ));
        assertThat(root.getCurrentStateName()).isEqualTo("Call");
        assertThat(attempt.getStatus())
                .isEqualTo(StateExecutionAttemptStatus.TIMED_OUT);
    }

    @Test
    void oversizedStateOutputFailsWithDataLimitExceeded() {
        useLimits(1024, 32, 1024, 1024, 1024, 1024);
        root.getWorkflowExecution().getWorkflowDefinition().setDefinition("""
                {
                  "StartAt": "Large",
                  "States": {
                    "Large": {
                      "Type": "Pass",
                      "Output": "{% 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' %}",
                      "End": true
                    }
                  }
                }
                """);
        root.setCurrentStateName("Large");

        InterpreterOutcome outcome = interpreter.advance(root.getId());

        assertThat(outcome).isInstanceOf(InterpreterOutcome.Failed.class);
        assertThat(((InterpreterOutcome.Failed) outcome).error())
                .isEqualTo("States.DataLimitExceeded");
        assertThat(root.getOutput()).isNull();
    }

    @Test
    void oversizedNextStateInputFailsEvenWhenOutputLimitAllowsIt() {
        useLimits(32, 1024, 1024, 1024, 1024, 1024);
        root.getWorkflowExecution().getWorkflowDefinition().setDefinition("""
                {
                  "StartAt": "Large",
                  "States": {
                    "Large": {
                      "Type": "Pass",
                      "Output": "{% 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' %}",
                      "Next": "Done"
                    },
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);
        root.setCurrentStateName("Large");

        InterpreterOutcome outcome = interpreter.advance(root.getId());

        assertThat(((InterpreterOutcome.Failed) outcome).error())
                .isEqualTo("States.DataLimitExceeded");
        assertThat(root.getCurrentStateName()).isEqualTo("Large");
    }

    @Test
    void oversizedVariablesFailWithDataLimitExceeded() {
        useLimits(1024, 1024, 32, 1024, 1024, 1024);
        root.getWorkflowExecution().getWorkflowDefinition().setDefinition("""
                {
                  "StartAt": "Large",
                  "States": {
                    "Large": {
                      "Type": "Pass",
                      "Assign": {
                        "large": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                      },
                      "Output": true,
                      "End": true
                    }
                  }
                }
                """);
        root.setCurrentStateName("Large");

        InterpreterOutcome outcome = interpreter.advance(root.getId());

        assertThat(((InterpreterOutcome.Failed) outcome).error())
                .isEqualTo("States.DataLimitExceeded");
    }

    @Test
    void oversizedTaskArgumentsFailBeforeDispatch() {
        useLimits(1024, 1024, 1024, 32, 1024, 1024);
        configureTaskDefinition("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "scheduler://webhook",
                      "Arguments": {
                        "value": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                      },
                      "End": true
                    }
                  }
                }
                """);

        InterpreterOutcome outcome = interpreter.advance(root.getId());

        assertThat(((InterpreterOutcome.Failed) outcome).error())
                .isEqualTo("States.DataLimitExceeded");
        assertThat(attempts).isEmpty();
    }

    @Test
    void oversizedTaskResultFailsWithoutPersistingResult() {
        useLimits(1024, 1024, 1024, 1024, 32, 1024);
        configureTaskDefinition("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "scheduler://webhook",
                      "End": true
                    }
                  }
                }
                """);
        interpreter.advance(root.getId());
        StateExecutionAttempt attempt = latestAttempt.get();
        attempt.setStatus(StateExecutionAttemptStatus.RUNNING);
        when(attemptRepository.findByIdForUpdate(attempt.getId()))
                .thenReturn(Optional.of(attempt));

        InterpreterOutcome outcome = interpreter.completeTaskSuccess(
                attempt.getId(),
                objectMapper.createObjectNode()
                        .put("value", "x".repeat(64))
        );

        assertThat(((InterpreterOutcome.Failed) outcome).error())
                .isEqualTo("States.DataLimitExceeded");
        assertThat(attempt.getResult()).isNull();
        assertThat(attempt.getStatus())
                .isEqualTo(StateExecutionAttemptStatus.FAILED);
    }

    @Test
    void oversizedTaskErrorDetailsBecomeDataLimitExceeded() {
        useLimits(1024, 1024, 1024, 1024, 1024, 32);
        configureTaskDefinition("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "scheduler://webhook",
                      "End": true
                    }
                  }
                }
                """);
        interpreter.advance(root.getId());
        StateExecutionAttempt attempt = latestAttempt.get();
        attempt.setStatus(StateExecutionAttemptStatus.RUNNING);
        when(attemptRepository.findByIdForUpdate(attempt.getId()))
                .thenReturn(Optional.of(attempt));

        InterpreterOutcome outcome = interpreter.completeTaskFailure(
                attempt.getId(),
                "Resource.Error",
                "x".repeat(64)
        );

        assertThat(((InterpreterOutcome.Failed) outcome).error())
                .isEqualTo("States.DataLimitExceeded");
        assertThat(attempt.getCause()).contains("UTF-8 bytes");
    }

    private void useLimits(
            long input,
            long output,
            long variables,
            long arguments,
            long result,
            long errors
    ) {
        AslJsonataEvaluator evaluator =
                new AslJsonataEvaluator(objectMapper, 100, 100);
        AslVariableAssignmentEvaluator assignmentEvaluator =
                new AslVariableAssignmentEvaluator(evaluator, objectMapper);
        AslDefinitionNavigator navigator =
                new AslDefinitionNavigator(objectMapper);
        interpreter = newInterpreter(
                evaluator,
                assignmentEvaluator,
                navigator,
                new WorkflowPayloadLimits(
                        objectMapper,
                        input,
                        output,
                        variables,
                        arguments,
                        result,
                        errors
                )
        );
    }

    private void configureTaskDefinition(String definition) {
        root.getWorkflowExecution().getWorkflowDefinition()
                .setDefinition(definition);
        root.setCurrentStateName("Call");
        root.setCurrentStateInput("{\"id\":\"100\"}");
    }

    private ExecutionScope rootScope() {
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID());

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(UUID.randomUUID());
        definition.setWorkflow(workflow);
        definition.setRevision(1);
        definition.setDefinition("""
                {
                  "StartAt": "Prepare",
                  "States": {
                    "Prepare": {
                      "Type": "Pass",
                      "Assign": {
                        "greeting": "hello"
                      },
                      "Output": {
                        "message": "{% $greeting & ' ' & $states.input.name %}"
                      },
                      "Next": "Done"
                    },
                    "Done": {
                      "Type": "Succeed",
                      "Output": "{% $states.input %}"
                    }
                  }
                }
                """);

        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID());
        execution.setWorkflow(workflow);
        execution.setWorkflowDefinition(definition);
        execution.setRunNumber(1);
        execution.setStatus(WorkflowExecutionStatus.PENDING);
        execution.setInput("{\"name\":\"Ada\"}");

        ExecutionScope scope = new ExecutionScope();
        scope.setId(UUID.randomUUID());
        scope.setWorkflowExecution(execution);
        scope.setScopeType(ExecutionScopeType.ROOT);
        scope.setScopePath("root");
        scope.setStatus(ExecutionScopeStatus.PENDING);
        scope.setCurrentStateName("Prepare");
        scope.setCurrentStateInput("{\"name\":\"Ada\"}");
        scope.setVariables("{\"greeting\":\"hi\"}");
        return scope;
    }
}
