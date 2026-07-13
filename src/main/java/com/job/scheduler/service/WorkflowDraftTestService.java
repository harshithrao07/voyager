package com.job.scheduler.service;

import com.job.scheduler.dto.DraftStateTestRequestDTO;
import com.job.scheduler.dto.DraftStateTestResponseDTO;
import com.job.scheduler.enums.AslStateType;
import com.job.scheduler.workflow.asl.runtime.AslJsonataEvaluator;
import com.job.scheduler.workflow.asl.runtime.AslVariableAssignmentEvaluator;
import com.job.scheduler.workflow.asl.runtime.StateExecutionContext;
import com.job.scheduler.workflow.asl.runtime.StateExecutor;
import com.job.scheduler.workflow.asl.runtime.StateOutcome;
import com.job.scheduler.workflow.task.TaskExecutionContext;
import com.job.scheduler.workflow.task.TaskResourceException;
import com.job.scheduler.workflow.task.TaskResourceRouter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowDraftTestService {
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String WAITING = "WAITING";
    private static final String TASK_PREVIEW = "TASK_PREVIEW";

    private final ObjectMapper objectMapper;
    private final AslJsonataEvaluator jsonataEvaluator;
    private final AslVariableAssignmentEvaluator assignmentEvaluator;
    private final TaskResourceRouter taskResourceRouter;
    private final Map<AslStateType, StateExecutor> executors;

    public WorkflowDraftTestService(
            ObjectMapper objectMapper,
            AslJsonataEvaluator jsonataEvaluator,
            AslVariableAssignmentEvaluator assignmentEvaluator,
            TaskResourceRouter taskResourceRouter,
            List<StateExecutor> stateExecutors
    ) {
        this.objectMapper = objectMapper;
        this.jsonataEvaluator = jsonataEvaluator;
        this.assignmentEvaluator = assignmentEvaluator;
        this.taskResourceRouter = taskResourceRouter;
        this.executors = new EnumMap<>(AslStateType.class);
        stateExecutors.forEach(executor -> this.executors.put(
                executor.supportedType(),
                executor
        ));
    }

    public DraftStateTestResponseDTO testState(
            DraftStateTestRequestDTO request
    ) {
        Instant startedAt = Instant.now();
        JsonNode definition = request.definition();
        JsonNode stateDefinition = findState(definition, request.stateName());
        AslStateType stateType = readStateType(stateDefinition);
        JsonNode input = jsonOrEmptyObject(request.input());
        JsonNode variables = variablesOrEmptyObject(request.variables());
        StateExecutionContext context = new StateExecutionContext(
                input,
                variables,
                previewContext(request.stateName())
        );

        if (stateType == AslStateType.PARALLEL
                || stateType == AslStateType.MAP) {
            return response(
                    FAILED,
                    request.stateName(),
                    stateType,
                    input,
                    null,
                    variables,
                    null,
                    null,
                    null,
                    null,
                    "States.RuntimeUnsupported",
                    stateType + " state previews are not supported yet",
                    startedAt
            );
        }

        StateExecutor executor = executors.get(stateType);
        if (executor == null) {
            throw new IllegalArgumentException(
                    "No draft test executor is available for " + stateType
            );
        }

        try {
            StateOutcome outcome = executor.execute(stateDefinition, context);
            if (outcome instanceof StateOutcome.DispatchTask task) {
                return testTask(
                        request,
                        stateDefinition,
                        input,
                        variables,
                        task,
                        startedAt
                );
            }
            if (outcome instanceof StateOutcome.Continue continued) {
                return response(
                        SUCCEEDED,
                        request.stateName(),
                        stateType,
                        input,
                        continued.output(),
                        continued.variables(),
                        continued.nextStateName(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        startedAt
                );
            }
            if (outcome instanceof StateOutcome.Waiting waiting) {
                return response(
                        WAITING,
                        request.stateName(),
                        stateType,
                        input,
                        waiting.output(),
                        waiting.variables(),
                        waiting.nextStateName(),
                        null,
                        null,
                        waiting.wakeAt(),
                        null,
                        null,
                        startedAt
                );
            }
            if (outcome instanceof StateOutcome.Fail failed) {
                return response(
                        FAILED,
                        request.stateName(),
                        stateType,
                        input,
                        null,
                        variables,
                        null,
                        null,
                        null,
                        null,
                        failed.error(),
                        failed.cause(),
                        startedAt
                );
            }

            StateOutcome.Succeed succeeded = (StateOutcome.Succeed) outcome;
            return response(
                    SUCCEEDED,
                    request.stateName(),
                    stateType,
                    input,
                    succeeded.output(),
                    succeeded.variables(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    startedAt
            );
        } catch (TaskResourceException exception) {
            return response(
                    FAILED,
                    request.stateName(),
                    stateType,
                    input,
                    null,
                    variables,
                    null,
                    stateDefinition.path("Resource").stringValue(),
                    null,
                    null,
                    exception.error(),
                    exception.getMessage(),
                    startedAt
            );
        } catch (RuntimeException exception) {
            return response(
                    FAILED,
                    request.stateName(),
                    stateType,
                    input,
                    null,
                    variables,
                    null,
                    null,
                    null,
                    null,
                    "States.QueryEvaluationError",
                    exception.getMessage(),
                    startedAt
            );
        }
    }

    private DraftStateTestResponseDTO testTask(
            DraftStateTestRequestDTO request,
            JsonNode stateDefinition,
            JsonNode input,
            JsonNode variables,
            StateOutcome.DispatchTask task,
            Instant startedAt
    ) {
        if (!Boolean.TRUE.equals(request.executeTask())) {
            return response(
                    TASK_PREVIEW,
                    request.stateName(),
                    AslStateType.TASK,
                    input,
                    null,
                    variables,
                    nextStateName(stateDefinition),
                    task.resource(),
                    task.arguments(),
                    null,
                    null,
                    null,
                    startedAt
            );
        }

        JsonNode result = taskResourceRouter.execute(
                task.resource(),
                task.arguments(),
                TaskExecutionContext.NONE
        );
        StateExecutionContext resultContext = new StateExecutionContext(
                input,
                variables,
                previewContext(request.stateName()),
                result,
                null
        );
        JsonNode nextVariables = assignmentEvaluator.apply(
                stateDefinition.get("Assign"),
                resultContext
        );
        JsonNode output = stateDefinition.has("Output")
                ? jsonataEvaluator.evaluate(
                        stateDefinition.get("Output"),
                        resultContext
                )
                : result;
        return response(
                SUCCEEDED,
                request.stateName(),
                AslStateType.TASK,
                input,
                output,
                nextVariables,
                nextStateName(stateDefinition),
                task.resource(),
                task.arguments(),
                null,
                null,
                null,
                startedAt
        );
    }

    private JsonNode findState(JsonNode definition, String stateName) {
        if (definition == null || !definition.isObject()) {
            throw new IllegalArgumentException("ASL definition must be an object");
        }
        JsonNode states = definition.get("States");
        if (states == null || !states.isObject()) {
            throw new IllegalArgumentException("ASL definition must contain a States object");
        }
        JsonNode state = states.get(stateName);
        if (state == null || !state.isObject()) {
            throw new IllegalArgumentException(
                    "State does not exist in the draft definition: " + stateName
            );
        }
        return state;
    }

    private AslStateType readStateType(JsonNode stateDefinition) {
        JsonNode type = stateDefinition.get("Type");
        if (type == null || !type.isString()) {
            throw new IllegalArgumentException("Draft state must define Type");
        }
        try {
            return AslStateType.valueOf(type.stringValue().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported draft state type: " + type.stringValue(),
                    exception
            );
        }
    }

    private JsonNode jsonOrEmptyObject(JsonNode value) {
        return value == null ? objectMapper.createObjectNode() : value.deepCopy();
    }

    private JsonNode variablesOrEmptyObject(JsonNode value) {
        if (value == null) {
            return objectMapper.createObjectNode();
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("Draft test variables must be an object");
        }
        return value.deepCopy();
    }

    private ObjectNode previewContext(String stateName) {
        ObjectNode context = objectMapper.createObjectNode();
        context.putObject("Execution")
                .put("Id", "draft-preview")
                .put("Mode", "DRAFT_TEST");
        context.putObject("State").put("Name", stateName);
        return context;
    }

    private String nextStateName(JsonNode stateDefinition) {
        if (stateDefinition.path("End").asBoolean(false)) {
            return null;
        }
        JsonNode next = stateDefinition.get("Next");
        return next != null && next.isString() ? next.stringValue() : null;
    }

    private DraftStateTestResponseDTO response(
            String status,
            String stateName,
            AslStateType stateType,
            JsonNode input,
            JsonNode output,
            JsonNode variables,
            String nextStateName,
            String taskResource,
            JsonNode taskArguments,
            Instant wakeAt,
            String error,
            String cause,
            Instant startedAt
    ) {
        return new DraftStateTestResponseDTO(
                status,
                stateName,
                stateType.name(),
                input,
                output,
                variables,
                nextStateName,
                taskResource,
                taskArguments,
                wakeAt,
                error,
                cause,
                Math.max(0, Duration.between(startedAt, Instant.now()).toMillis())
        );
    }
}
