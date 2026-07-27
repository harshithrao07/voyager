package com.job.scheduler.controller;

import com.job.scheduler.dto.WorkflowDefinitionResponseDTO;
import com.job.scheduler.dto.WorkflowExecutionResponseDTO;
import com.job.scheduler.dto.WorkflowResponseDTO;
import com.job.scheduler.dto.WorkflowExecutionDetailDTO;
import com.job.scheduler.dto.WorkflowExecutionPageDTO;
import com.job.scheduler.dto.WorkflowExecutionSummaryDTO;
import com.job.scheduler.dto.WorkflowExecutionCancellationResponseDTO;
import com.job.scheduler.dto.WorkflowPageDTO;
import com.job.scheduler.dto.DraftStateTestResponseDTO;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionTrigger;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.exception.ApiExceptionHandler;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.service.WorkflowAiFailureTriageService;
import com.job.scheduler.service.WorkflowDraftTestService;
import com.job.scheduler.service.WorkflowExecutionInspectionService;
import com.job.scheduler.service.WorkflowExecutionCancellationService;
import com.job.scheduler.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkflowControllerTest {
    @Mock
    private WorkflowService workflowService;
    @Mock
    private WorkflowExecutionRunner workflowExecutionRunner;
    @Mock
    private WorkflowExecutionInspectionService workflowExecutionInspectionService;
    @Mock
    private WorkflowExecutionCancellationService
            workflowExecutionCancellationService;
    @Mock
    private WorkflowDraftTestService workflowDraftTestService;
    @Mock
    private WorkflowAiFailureTriageService workflowAiFailureTriageService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkflowController(
                        workflowService,
                        workflowExecutionRunner,
                        workflowExecutionInspectionService,
                        workflowExecutionCancellationService,
                        workflowDraftTestService,
                        workflowAiFailureTriageService
                ))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void testsOneDraftStateWithoutCreatingAWorkflow() throws Exception {
        var output = objectMapper.createObjectNode().put("shaped", true);
        when(workflowDraftTestService.testState(any())).thenReturn(
                new DraftStateTestResponseDTO(
                        "SUCCEEDED",
                        "Shape",
                        "PASS",
                        objectMapper.createObjectNode(),
                        output,
                        objectMapper.createObjectNode(),
                        "Next",
                        null,
                        null,
                        null,
                        null,
                        null,
                        2
                )
        );

        mockMvc.perform(post("/app/v1/workflows/draft-tests/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definition": {
                                    "States": {
                                      "Shape": {
                                        "Type": "Pass",
                                        "Next": "Next"
                                      }
                                    }
                                  },
                                  "stateName": "Shape",
                                  "input": {"value": 1}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.stateName").value("Shape"))
                .andExpect(jsonPath("$.output.shaped").value(true))
                .andExpect(jsonPath("$.nextStateName").value("Next"));
    }

    @Test
    void createsAslOnlyDraftWorkflow() throws Exception {
        UUID workflowId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        var definition = objectMapper.readTree("""
                {
                  "StartAt": "Done",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);
        var definitionResponse = new WorkflowDefinitionResponseDTO(
                definitionId,
                1,
                "a".repeat(64),
                definition,
                objectMapper.createObjectNode(),
                true,
                Instant.now()
        );
        when(workflowService.createWorkflow(any())).thenReturn(new WorkflowResponseDTO(
                workflowId,
                0,
                "Simple workflow",
                WorkflowStatus.DRAFT,
                null,
                "UTC",
                null,
                3,
                "workflow-api-1",
                definitionResponse,
                Instant.now(),
                Instant.now()
        ));

        mockMvc.perform(post("/app/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Simple workflow",
                                  "maxAttempts": 3,
                                  "idempotencyKey": "workflow-api-1",
                                  "definition": {
                                    "StartAt": "Done",
                                    "States": {
                                      "Done": {"Type": "Succeed"}
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workflowId.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.activeDefinition.revision").value(1));
    }

    @Test
    void activatesSupportedRevision() throws Exception {
        UUID workflowId = UUID.randomUUID();
        var definition = objectMapper.readTree("""
                {
                  "StartAt": "Done",
                  "States": {
                    "Done": {"Type": "Succeed"}
                  }
                }
                """);
        when(workflowService.activateRevision(eq(workflowId), eq(1L)))
                .thenReturn(new WorkflowDefinitionResponseDTO(
                        UUID.randomUUID(),
                        1,
                        "a".repeat(64),
                        definition,
                        objectMapper.createObjectNode(),
                        true,
                        Instant.now()
                ));

        mockMvc.perform(post(
                                "/app/v1/workflows/{workflowId}/revisions/{revision}/activate",
                                workflowId,
                                1
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void updatesRevisionCanvasLayout() throws Exception {
        UUID workflowId = UUID.randomUUID();
        var definition = objectMapper.readTree("""
                {
                  "StartAt": "Done",
                  "States": {"Done": {"Type": "Succeed"}}
                }
                """);
        var canvasLayout = objectMapper.readTree("""
                {"Done": {"x": 120.5, "y": -40}}
                """);
        when(workflowService.updateCanvasLayout(
                eq(workflowId),
                eq(1L),
                any()
        )).thenReturn(new WorkflowDefinitionResponseDTO(
                UUID.randomUUID(),
                1,
                "a".repeat(64),
                definition,
                canvasLayout,
                true,
                Instant.now()
        ));

        mockMvc.perform(put(
                                "/app/v1/workflows/{workflowId}/revisions/{revision}/canvas-layout",
                                workflowId,
                                1
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"positions": {"Done": {"x": 120.5, "y": -40}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canvasLayout.Done.x").value(120.5))
                .andExpect(jsonPath("$.canvasLayout.Done.y").value(-40));
    }

    @Test
    void startsManualWorkflowExecution() throws Exception {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        var output = objectMapper.createObjectNode().put("approved", true);
        when(workflowExecutionRunner.start(eq(workflowId), any()))
                .thenReturn(new WorkflowExecutionResponseDTO(
                        executionId,
                        WorkflowExecutionStatus.SUCCEEDED,
                        output,
                        null,
                        null,
                        null,
                        null
                ));

        mockMvc.perform(post(
                                "/app/v1/workflows/{workflowId}/executions",
                                workflowId
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "input": {
                                    "total": 125
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowExecutionId")
                        .value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.output.approved").value(true));
    }

    @Test
    void listsWorkflowExecutions() throws Exception {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(workflowExecutionInspectionService.listExecutions(
                workflowId,
                0,
                20,
                null,
                null,
                null,
                null
        )).thenReturn(new WorkflowExecutionPageDTO(
                List.of(summary(workflowId, executionId)),
                0,
                20,
                1,
                1,
                true,
                true
        ));

        mockMvc.perform(get(
                        "/app/v1/workflows/{workflowId}/executions",
                        workflowId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id")
                        .value(executionId.toString()))
                .andExpect(jsonPath("$.content[0].runNumber").value(3))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void forwardsWorkflowExecutionFilters() throws Exception {
        UUID workflowId = UUID.randomUUID();
        when(workflowExecutionInspectionService.listExecutions(
                workflowId,
                2,
                10,
                WorkflowExecutionStatus.FAILED,
                3L,
                WorkflowExecutionTrigger.SCHEDULED,
                "7"
        )).thenReturn(new WorkflowExecutionPageDTO(
                List.of(),
                2,
                10,
                0,
                0,
                false,
                true
        ));

        mockMvc.perform(get(
                        "/app/v1/workflows/{workflowId}/executions",
                        workflowId
                )
                .param("page", "2")
                .param("size", "10")
                .param("status", "FAILED")
                .param("revision", "3")
                .param("trigger", "SCHEDULED")
                .param("search", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        verify(workflowExecutionInspectionService).listExecutions(
                workflowId,
                2,
                10,
                WorkflowExecutionStatus.FAILED,
                3L,
                WorkflowExecutionTrigger.SCHEDULED,
                "7"
        );
    }

    @Test
    void getsWorkflowExecutionTree() throws Exception {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(workflowExecutionInspectionService.getExecution(
                workflowId,
                executionId
        )).thenReturn(new WorkflowExecutionDetailDTO(
                summary(workflowId, executionId),
                List.of()
        ));

        mockMvc.perform(get(
                        "/app/v1/workflows/{workflowId}/executions/{executionId}",
                        workflowId,
                        executionId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.id")
                        .value(executionId.toString()))
                .andExpect(jsonPath("$.execution.status")
                        .value("SUCCEEDED"))
                .andExpect(jsonPath("$.scopes").isArray());
    }

    @Test
    void pausesWorkflow() throws Exception {
        UUID workflowId = UUID.randomUUID();
        when(workflowService.pauseWorkflow(workflowId))
                .thenReturn(workflowResponse(
                        workflowId,
                        WorkflowStatus.PAUSED,
                        null
                ));

        mockMvc.perform(post(
                        "/app/v1/workflows/{workflowId}/pause",
                        workflowId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist());
    }

    @Test
    void resumesWorkflow() throws Exception {
        UUID workflowId = UUID.randomUUID();
        Instant nextRunAt = Instant.now().plusSeconds(3600);
        when(workflowService.resumeWorkflow(workflowId))
                .thenReturn(workflowResponse(
                        workflowId,
                        WorkflowStatus.ACTIVE,
                        nextRunAt
                ));

        mockMvc.perform(post(
                        "/app/v1/workflows/{workflowId}/resume",
                        workflowId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.nextRunAt").exists());
    }

    @Test
    void archivesWorkflow() throws Exception {
        UUID workflowId = UUID.randomUUID();
        when(workflowService.archiveWorkflow(workflowId))
                .thenReturn(workflowResponse(
                        workflowId,
                        WorkflowStatus.ARCHIVED,
                        null
                ));

        mockMvc.perform(post(
                        "/app/v1/workflows/{workflowId}/archive",
                        workflowId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void cancelsWorkflowExecution() throws Exception {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        Instant completedAt = Instant.now();
        when(workflowExecutionCancellationService.cancelExecution(
                workflowId,
                executionId
        )).thenReturn(new WorkflowExecutionCancellationResponseDTO(
                executionId,
                WorkflowExecutionStatus.CANCELED,
                "Execution.Canceled",
                "Workflow execution was canceled by user request",
                completedAt
        ));

        mockMvc.perform(post(
                        "/app/v1/workflows/{workflowId}/executions/{executionId}/cancel",
                        workflowId,
                        executionId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowExecutionId")
                        .value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.error")
                        .value("Execution.Canceled"));
    }

    @Test
    void listsFilteredWorkflows() throws Exception {
        UUID workflowId = UUID.randomUUID();
        WorkflowResponseDTO workflow = workflowResponse(
                workflowId,
                WorkflowStatus.ACTIVE,
                Instant.now().plusSeconds(3600)
        );
        when(workflowService.listWorkflows(
                0,
                20,
                WorkflowStatus.ACTIVE,
                "invoice"
        )).thenReturn(new WorkflowPageDTO(
                List.of(workflow),
                0,
                20,
                1,
                1,
                true,
                true
        ));

        mockMvc.perform(get("/app/v1/workflows")
                        .param("status", "ACTIVE")
                        .param("name", "invoice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id")
                        .value(workflowId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void updatesWorkflowMetadata() throws Exception {
        UUID workflowId = UUID.randomUUID();
        WorkflowResponseDTO workflow = workflowResponse(
                workflowId,
                WorkflowStatus.ACTIVE,
                Instant.now().plusSeconds(3600)
        );
        when(workflowService.updateMetadata(eq(workflowId), any()))
                .thenReturn(workflow);

        mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.patch(
                                "/app/v1/workflows/{workflowId}",
                                workflowId
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "name": "Updated workflow",
                                  "cronExpression": "0 0 * * * *",
                                  "timezone": "UTC",
                                  "maxAttempts": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(workflowId.toString()))
                .andExpect(jsonPath("$.version").value(0));
    }

    private WorkflowExecutionSummaryDTO summary(
            UUID workflowId,
            UUID executionId
    ) {
        Instant now = Instant.now();
        return new WorkflowExecutionSummaryDTO(
                executionId,
                workflowId,
                UUID.randomUUID(),
                2,
                3,
                WorkflowExecutionStatus.SUCCEEDED,
                now,
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode().put("ok", true),
                null,
                null,
                null,
                now,
                now,
                now,
                now
        );
    }

    private WorkflowResponseDTO workflowResponse(
            UUID workflowId,
            WorkflowStatus status,
            Instant nextRunAt
    ) {
        Instant now = Instant.now();
        return new WorkflowResponseDTO(
                workflowId,
                0,
                "Workflow",
                status,
                "0 0 * * * *",
                "UTC",
                nextRunAt,
                3,
                "workflow-lifecycle-" + workflowId,
                null,
                now,
                now
        );
    }
}
