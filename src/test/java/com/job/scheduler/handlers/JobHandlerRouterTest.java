package com.job.scheduler.handlers;

import com.job.scheduler.dto.JobDispatchEvent;
import com.job.scheduler.dto.payload.CleanupPayload;
import com.job.scheduler.dto.payload.McpToolPayload;
import com.job.scheduler.dto.payload.SendEmailPayload;
import com.job.scheduler.dto.payload.WebhookPayload;
import com.job.scheduler.entity.ExecutionLog;
import com.job.scheduler.entity.Job;
import com.job.scheduler.entity.JobStep;
import com.job.scheduler.entity.StepExecution;
import com.job.scheduler.enums.JobType;
import com.job.scheduler.service.JobService;
import com.job.scheduler.service.StepExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobHandlerRouterTest {

    @Mock
    private JobService jobService;

    @Mock
    private SendEmailHandler sendEmailHandler;

    @Mock
    private WebhookHandler webhookHandler;

    @Mock
    private CleanupHandler cleanupHandler;

    @Mock
    private McpToolHandler mcpToolHandler;

    @Mock
    private StepExecutionService stepExecutionService;

    private JobHandlerRouter jobHandlerRouter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jobHandlerRouter = new JobHandlerRouter(
                objectMapper,
                jobService,
                sendEmailHandler,
                webhookHandler,
                cleanupHandler,
                mcpToolHandler,
                stepExecutionService
        );
    }

    @Test
    void routeDelegatesSendEmailPayloadToSendEmailHandler() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job();
        job.setId(jobId);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("to", "user@example.com");
        payload.put("subject", "hello");
        payload.put("body", "world");
        JobStep step = step(JobType.SEND_EMAIL, payload.toString());

        when(jobService.findById(jobId)).thenReturn(job);
        when(jobService.getEnabledSteps(job)).thenReturn(List.of(step));

        jobHandlerRouter.route(new JobDispatchEvent(jobId));

        ArgumentCaptor<SendEmailPayload> captor = ArgumentCaptor.forClass(SendEmailPayload.class);
        verify(sendEmailHandler).handle(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("user@example.com");
        assertThat(captor.getValue().subject()).isEqualTo("hello");
        assertThat(captor.getValue().body()).isEqualTo("world");
    }

    @Test
    void routeDelegatesWebhookPayloadToWebhookHandler() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job();
        job.setId(jobId);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("message", "ping");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("url", "https://example.com/hook");
        payload.set("body", body);
        JobStep step = step(JobType.WEBHOOK, payload.toString());

        when(jobService.findById(jobId)).thenReturn(job);
        when(jobService.getEnabledSteps(job)).thenReturn(List.of(step));

        jobHandlerRouter.route(new JobDispatchEvent(jobId));

        ArgumentCaptor<WebhookPayload> captor = ArgumentCaptor.forClass(WebhookPayload.class);
        verify(webhookHandler).handle(captor.capture());
        assertThat(captor.getValue().url()).isEqualTo("https://example.com/hook");
        assertThat(captor.getValue().body().get("message").stringValue()).isEqualTo("ping");
    }

    @Test
    void routeDelegatesCleanupPayloadToCleanupHandler() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job();
        job.setId(jobId);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("olderThanDays", 30);
        JobStep step = step(JobType.CLEANUP, payload.toString());

        when(jobService.findById(jobId)).thenReturn(job);
        when(jobService.getEnabledSteps(job)).thenReturn(List.of(step));

        jobHandlerRouter.route(new JobDispatchEvent(jobId));

        ArgumentCaptor<CleanupPayload> captor = ArgumentCaptor.forClass(CleanupPayload.class);
        verify(cleanupHandler).handle(captor.capture());
        assertThat(captor.getValue().olderThanDays()).isEqualTo(30);
    }

    @Test
    void routeDelegatesMcpToolPayloadToMcpToolHandlerWithExecutionLog() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job();
        job.setId(jobId);
        ExecutionLog executionLog = new ExecutionLog();
        StepExecution stepExecution = new StepExecution();

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("message", "hello");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("serverId", "local-tools");
        payload.put("toolName", "ping");
        payload.set("arguments", arguments);
        payload.put("maxAllowedTrustLevel", "READ_ONLY");
        JobStep step = step(JobType.MCP_TOOL, payload.toString());

        when(jobService.findById(jobId)).thenReturn(job);
        when(jobService.getEnabledSteps(job)).thenReturn(List.of(step));
        when(stepExecutionService.start(executionLog, step)).thenReturn(stepExecution);

        jobHandlerRouter.route(new JobDispatchEvent(jobId), executionLog);

        ArgumentCaptor<McpToolPayload> captor = ArgumentCaptor.forClass(McpToolPayload.class);
        verify(mcpToolHandler).handle(
                captor.capture(),
                org.mockito.ArgumentMatchers.eq(executionLog),
                org.mockito.ArgumentMatchers.eq(stepExecution)
        );
        verify(stepExecutionService).markSuccess(stepExecution);
        assertThat(captor.getValue().serverId()).isEqualTo("local-tools");
        assertThat(captor.getValue().toolName()).isEqualTo("ping");
        assertThat(captor.getValue().arguments().get("message").stringValue()).isEqualTo("hello");
    }

    @Test
    void routeThrowsWhenPayloadShapeIsInvalid() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job();
        job.setId(jobId);
        JobStep step = step(JobType.WEBHOOK, objectMapper.createArrayNode().add("not-an-object").toString());

        when(jobService.findById(jobId)).thenReturn(job);
        when(jobService.getEnabledSteps(job)).thenReturn(List.of(step));

        JobDispatchEvent event = new JobDispatchEvent(jobId);

        assertThatThrownBy(() -> jobHandlerRouter.route(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payload does not match expected shape");
    }

    private JobStep step(JobType stepType, String payload) {
        JobStep step = new JobStep();
        step.setId(UUID.randomUUID());
        step.setStepOrder(1);
        step.setStepType(stepType);
        step.setPayload(payload);
        step.setEnabled(true);
        return step;
    }
}
