package com.job.scheduler.handlers;

import tools.jackson.databind.ObjectMapper;
import com.job.scheduler.dto.JobDispatchEvent;
import com.job.scheduler.dto.payload.*;
import com.job.scheduler.entity.ExecutionLog;
import com.job.scheduler.entity.Job;
import com.job.scheduler.entity.JobStep;
import com.job.scheduler.entity.StepExecution;
import com.job.scheduler.service.JobService;
import com.job.scheduler.service.StepExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JobHandlerRouter {

    private final ObjectMapper objectMapper;
    private final JobService jobService;
    private final SendEmailHandler sendEmailHandler;
    private final WebhookHandler webhookHandler;
    private final CleanupHandler cleanupHandler;
    private final McpToolHandler mcpToolHandler;
    private final StepExecutionService stepExecutionService;

    public void route(JobDispatchEvent jobDispatchEvent) {
        route(jobDispatchEvent, null);
    }

    public void route(JobDispatchEvent jobDispatchEvent, ExecutionLog executionLog) {
        Job job = jobService.findById(jobDispatchEvent.jobId());

        for (JobStep step : jobService.getEnabledSteps(job)) {
            StepExecution stepExecution = executionLog == null ? null : stepExecutionService.start(executionLog, step);
            try {
                switch (step.getStepType()) {
                    case SEND_EMAIL -> sendEmailHandler.handle(
                            readPayload(step.getPayload(), SendEmailPayload.class)
                    );
                    case WEBHOOK -> webhookHandler.handle(
                            readPayload(step.getPayload(), WebhookPayload.class)
                    );
                    case CLEANUP -> cleanupHandler.handle(
                            readPayload(step.getPayload(), CleanupPayload.class)
                    );
                    case MCP_TOOL -> mcpToolHandler.handle(
                            readPayload(step.getPayload(), McpToolPayload.class),
                            executionLog,
                            stepExecution
                    );
                    default -> throw new IllegalArgumentException("Unsupported step type: " + step.getStepType());
                }
                if (stepExecution != null) {
                    stepExecutionService.markSuccess(stepExecution);
                }
            } catch (RuntimeException exception) {
                if (stepExecution != null) {
                    stepExecutionService.markFailed(stepExecution, exception);
                }
                throw exception;
            }
        }
    }

    private <T> T readPayload(String payload, Class<T> payloadClass) {
        try {
            return objectMapper.readValue(payload, payloadClass);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Payload does not match expected shape for " + payloadClass.getSimpleName(), e
            );
        }
    }
}
