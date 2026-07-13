package com.job.scheduler.service;

import com.job.scheduler.dto.DraftStateTestRequestDTO;
import com.job.scheduler.workflow.asl.runtime.AslJsonataEvaluator;
import com.job.scheduler.workflow.asl.runtime.AslVariableAssignmentEvaluator;
import com.job.scheduler.workflow.asl.runtime.ChoiceStateExecutor;
import com.job.scheduler.workflow.asl.runtime.FailStateExecutor;
import com.job.scheduler.workflow.asl.runtime.PassStateExecutor;
import com.job.scheduler.workflow.asl.runtime.StateExecutor;
import com.job.scheduler.workflow.asl.runtime.SucceedStateExecutor;
import com.job.scheduler.workflow.asl.runtime.TaskStateExecutor;
import com.job.scheduler.workflow.asl.runtime.WaitStateExecutor;
import com.job.scheduler.workflow.task.TaskResourceRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowDraftTestServiceTest {
    private ObjectMapper objectMapper;
    private TaskResourceRouter taskResourceRouter;
    private WorkflowDraftTestService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AslJsonataEvaluator jsonataEvaluator = new AslJsonataEvaluator(
                objectMapper,
                100,
                100
        );
        AslVariableAssignmentEvaluator assignmentEvaluator =
                new AslVariableAssignmentEvaluator(
                        jsonataEvaluator,
                        objectMapper
                );
        taskResourceRouter = mock(TaskResourceRouter.class);
        List<StateExecutor> executors = List.of(
                new PassStateExecutor(jsonataEvaluator, assignmentEvaluator),
                new ChoiceStateExecutor(jsonataEvaluator, assignmentEvaluator),
                new WaitStateExecutor(jsonataEvaluator, assignmentEvaluator),
                new TaskStateExecutor(jsonataEvaluator),
                new SucceedStateExecutor(jsonataEvaluator),
                new FailStateExecutor(jsonataEvaluator)
        );
        service = new WorkflowDraftTestService(
                objectMapper,
                jsonataEvaluator,
                assignmentEvaluator,
                taskResourceRouter,
                executors
        );
    }

    @Test
    void previewsSingleStateFromIncompleteDefinition() throws Exception {
        var definition = objectMapper.readTree("""
                {
                  "States": {
                    "Shape": {
                      "Type": "Pass",
                      "Output": {
                        "customerId": "{% $states.input.id %}",
                        "ready": true
                      },
                      "Next": "MissingNextState"
                    }
                  }
                }
                """);
        var input = objectMapper.readTree("{" + "\"id\":42" + "}");

        var response = service.testState(new DraftStateTestRequestDTO(
                definition,
                "Shape",
                input,
                null,
                false
        ));

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.output().path("customerId").intValue()).isEqualTo(42);
        assertThat(response.output().path("ready").booleanValue()).isTrue();
        assertThat(response.nextStateName()).isEqualTo("MissingNextState");
    }

    @Test
    void reportsChoiceRouteAndCarriesOutputForward() throws Exception {
        var definition = objectMapper.readTree("""
                {
                  "States": {
                    "Route": {
                      "Type": "Choice",
                      "Choices": [
                        {
                          "Condition": "{% $states.input.total >= 1000 %}",
                          "Next": "Review",
                          "Output": {"tier": "manual"}
                        }
                      ],
                      "Default": "Approve"
                    }
                  }
                }
                """);

        var response = service.testState(new DraftStateTestRequestDTO(
                definition,
                "Route",
                objectMapper.readTree("{" + "\"total\":1250" + "}"),
                null,
                false
        ));

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.nextStateName()).isEqualTo("Review");
        assertThat(response.output().path("tier").stringValue())
                .isEqualTo("manual");
    }

    @Test
    void taskPreviewEvaluatesArgumentsWithoutExecutingResource() throws Exception {
        var definition = objectMapper.readTree("""
                {
                  "States": {
                    "Send": {
                      "Type": "Task",
                      "Resource": "webhook://send",
                      "Arguments": {"id": "{% $states.input.id %}"},
                      "Next": "Done"
                    }
                  }
                }
                """);

        var response = service.testState(new DraftStateTestRequestDTO(
                definition,
                "Send",
                objectMapper.readTree("{" + "\"id\":7" + "}"),
                null,
                false
        ));

        assertThat(response.status()).isEqualTo("TASK_PREVIEW");
        assertThat(response.taskArguments().path("id").intValue()).isEqualTo(7);
        assertThat(response.nextStateName()).isEqualTo("Done");
        verifyNoInteractions(taskResourceRouter);
    }

    @Test
    void executesTaskOnlyWhenExplicitlyEnabled() throws Exception {
        var definition = objectMapper.readTree("""
                {
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "function://orders/normalize",
                      "Output": {"normalized": "{% $states.result.value %}"},
                      "End": true
                    }
                  }
                }
                """);
        when(taskResourceRouter.execute(
                org.mockito.ArgumentMatchers.eq("function://orders/normalize"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(objectMapper.readTree("{" + "\"value\":\"ok\"" + "}"));

        var response = service.testState(new DraftStateTestRequestDTO(
                definition,
                "Call",
                objectMapper.createObjectNode(),
                null,
                true
        ));

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.output().path("normalized").stringValue())
                .isEqualTo("ok");
        assertThat(response.nextStateName()).isNull();
    }
}
