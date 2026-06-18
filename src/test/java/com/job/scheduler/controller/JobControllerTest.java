package com.job.scheduler.controller;

import com.job.scheduler.dto.CancelJobResponseDTO;
import com.job.scheduler.dto.ExecutionLogDTO;
import com.job.scheduler.dto.JobDetailDTO;
import com.job.scheduler.dto.JobPageDTO;
import com.job.scheduler.dto.JobStepResponseDTO;
import com.job.scheduler.dto.JobSummaryDTO;
import com.job.scheduler.dto.McpToolExecutionDetailDTO;
import com.job.scheduler.dto.RequeueJobResponseDTO;
import com.job.scheduler.dto.StepExecutionDTO;
import com.job.scheduler.dto.WorkflowJobRequestDTO;
import com.job.scheduler.enums.JobPriority;
import com.job.scheduler.enums.JobStatus;
import com.job.scheduler.enums.JobType;
import com.job.scheduler.exception.ApiExceptionHandler;
import com.job.scheduler.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class JobControllerTest {

    @Mock
    private JobService jobService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new JobController(jobService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void submitJobReturnsJobId() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.submitJob(org.mockito.ArgumentMatchers.any())).thenReturn(jobId);

        String body = """
                {
                  "jobType": "WEBHOOK",
                  "jobPriority": "MEDIUM",
                  "payload": {
                    "url": "https://example.com/hook",
                    "body": {
                      "message": "ping"
                    }
                  },
                  "idempotencyKey": "controller-job-1"
                }
                """;

        mockMvc.perform(post("/app/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(jobId.toString()));
    }

    @Test
    void submitWorkflowJobReturnsJobId() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.submitWorkflowJob(org.mockito.ArgumentMatchers.any(WorkflowJobRequestDTO.class))).thenReturn(jobId);

        String body = """
                {
                  "jobPriority": "MEDIUM",
                  "maxAttempts": 3,
                  "idempotencyKey": "workflow-controller-job-1",
                  "steps": [
                    {
                      "stepOrder": 1,
                      "stepType": "CLEANUP",
                      "payload": {
                        "olderThanDays": 7
                      }
                    },
                    {
                      "stepOrder": 2,
                      "stepType": "WEBHOOK",
                      "payload": {
                        "url": "https://example.com/hook",
                        "body": {
                          "message": "cleanup complete"
                        }
                      }
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/app/v1/jobs/workflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(jobId.toString()));
    }

    @Test
    void submitWorkflowJobValidatesEmptySteps() throws Exception {
        String body = """
                {
                  "jobPriority": "MEDIUM",
                  "idempotencyKey": "workflow-controller-job-empty",
                  "steps": []
                }
                """;

        mockMvc.perform(post("/app/v1/jobs/workflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void getJobsReturnsPagedResponse() throws Exception {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-26T00:00:00Z");

        JobSummaryDTO summary = new JobSummaryDTO(
                jobId,
                JobType.WEBHOOK,
                JobStatus.PENDING,
                JobPriority.MEDIUM,
                null,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );

        JobPageDTO page = new JobPageDTO(List.of(summary), 0, 20, 1, 1, true, true);

        when(jobService.getJobs(
                JobStatus.PENDING,
                JobType.WEBHOOK,
                JobPriority.MEDIUM,
                null,
                null,
                0,
                20
        )).thenReturn(page);

        mockMvc.perform(get("/app/v1/jobs")
                        .param("status", "PENDING")
                        .param("type", "WEBHOOK")
                        .param("priority", "MEDIUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(jobId.toString()))
                .andExpect(jsonPath("$.content[0].jobType").value("WEBHOOK"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void getJobReturnsJobDetail() throws Exception {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-26T00:00:00Z");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("url", "https://example.com/hook");
        JobStepResponseDTO step = new JobStepResponseDTO(
                UUID.randomUUID(),
                1,
                JobType.WEBHOOK,
                payload,
                true,
                now,
                now
        );

        JobDetailDTO detail = new JobDetailDTO(
                jobId,
                JobType.WEBHOOK,
                JobStatus.PENDING,
                JobPriority.MEDIUM,
                payload,
                null,
                3,
                "job-key-1",
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(step),
                now,
                now
        );

        when(jobService.getJob(jobId)).thenReturn(detail);

        mockMvc.perform(get("/app/v1/jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.jobType").value("WEBHOOK"))
                .andExpect(jsonPath("$.jobStatus").value("PENDING"))
                .andExpect(jsonPath("$.payload.url").value("https://example.com/hook"))
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps[0].stepOrder").value(1))
                .andExpect(jsonPath("$.steps[0].stepType").value("WEBHOOK"))
                .andExpect(jsonPath("$.steps[0].payload.url").value("https://example.com/hook"));
    }

    @Test
    void getExecutionLogsReturnsStepExecutions() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID executionLogId = UUID.randomUUID();
        UUID jobStepId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-26T00:00:00Z");
        ObjectNode arguments = objectMapper.createObjectNode().put("message", "hello");
        ObjectNode result = objectMapper.createObjectNode().put("ok", true);
        McpToolExecutionDetailDTO details = new McpToolExecutionDetailDTO(
                UUID.randomUUID(),
                "local-tools",
                "ping",
                arguments,
                result,
                com.job.scheduler.enums.McpToolExecutionStatus.SUCCESS,
                com.job.scheduler.enums.McpTrustLevel.READ_ONLY,
                null,
                now,
                now.plusSeconds(1),
                1000L,
                now,
                now.plusSeconds(1)
        );

        StepExecutionDTO stepExecution = new StepExecutionDTO(
                UUID.randomUUID(),
                jobStepId,
                1,
                JobType.MCP_TOOL,
                JobStatus.SUCCESS,
                now,
                now.plusSeconds(1),
                1000L,
                null,
                objectMapper.createObjectNode().put("message", "hello"),
                null,
                objectMapper.createObjectNode().put("ok", true),
                null,
                now,
                details
        );
        ExecutionLogDTO executionLog = new ExecutionLogDTO(
                executionLogId,
                jobId,
                1,
                JobStatus.SUCCESS,
                now,
                now.plusSeconds(1),
                1000L,
                null,
                "worker-1",
                now,
                List.of(stepExecution)
        );

        when(jobService.getExecutionLogs(jobId)).thenReturn(List.of(executionLog));

        mockMvc.perform(get("/app/v1/jobs/{jobId}/logs", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(executionLogId.toString()))
                .andExpect(jsonPath("$[0].steps.length()").value(1))
                .andExpect(jsonPath("$[0].steps[0].jobStepId").value(jobStepId.toString()))
                .andExpect(jsonPath("$[0].steps[0].stepOrder").value(1))
                .andExpect(jsonPath("$[0].steps[0].stepType").value("MCP_TOOL"))
                .andExpect(jsonPath("$[0].steps[0].executionStatus").value("SUCCESS"))
                .andExpect(jsonPath("$[0].steps[0].durationMs").value(1000))
                .andExpect(jsonPath("$[0].steps[0].resolvedInput.message").value("hello"))
                .andExpect(jsonPath("$[0].steps[0].output.ok").value(true))
                .andExpect(jsonPath("$[0].steps[0].details.kind").value("MCP_TOOL"))
                .andExpect(jsonPath("$[0].steps[0].details.serverId").value("local-tools"))
                .andExpect(jsonPath("$[0].steps[0].details.toolName").value("ping"))
                .andExpect(jsonPath("$[0].steps[0].details.arguments.message").value("hello"))
                .andExpect(jsonPath("$[0].steps[0].details.result.ok").value(true));
    }

    @Test
    void cancelJobReturnsCanceledStatus() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.cancelJob(jobId)).thenReturn(new CancelJobResponseDTO(jobId, JobStatus.CANCELED));

        mockMvc.perform(post("/app/v1/jobs/{jobId}/cancel", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void requeueJobReturnsNewJobId() throws Exception {
        UUID originalJobId = UUID.randomUUID();
        UUID requeuedJobId = UUID.randomUUID();
        when(jobService.requeueJob(originalJobId)).thenReturn(new RequeueJobResponseDTO(requeuedJobId));

        mockMvc.perform(post("/app/v1/jobs/{jobId}/requeue", originalJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(requeuedJobId.toString()));
    }
}
