package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionLog;
import com.job.scheduler.entity.JobStep;
import com.job.scheduler.entity.StepExecution;
import com.job.scheduler.enums.JobStatus;
import com.job.scheduler.repository.StepExecutionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StepExecutionService {
    private final StepExecutionRepository stepExecutionRepository;
    private final WorkflowPayloadStorageService workflowPayloadStorageService;

    public StepExecution start(ExecutionLog executionLog, JobStep jobStep) {
        StepExecution stepExecution = new StepExecution();
        stepExecution.setExecutionLog(executionLog);
        stepExecution.setJobStep(jobStep);
        stepExecution.setStepOrder(jobStep.getStepOrder());
        stepExecution.setStepType(jobStep.getStepType());
        stepExecution.setExecutionStatus(JobStatus.RUNNING);
        stepExecution.setStartedAt(Instant.now());
        var storedInput = workflowPayloadStorageService.store(
                payloadKey(executionLog, jobStep, "input"),
                jobStep.getPayload()
        );
        stepExecution.setResolvedInput(storedInput.inlineValue());
        stepExecution.setInputRef(storedInput.reference());

        return stepExecutionRepository.save(stepExecution);
    }

    @Transactional
    public void markSuccess(StepExecution stepExecution) {
        stepExecution.setExecutionStatus(JobStatus.SUCCESS);
        stepExecution.setErrorMessage(null);
        complete(stepExecution);
        stepExecutionRepository.save(stepExecution);
    }

    @Transactional
    public void markSuccess(StepExecution stepExecution, String output) {
        var storedOutput = workflowPayloadStorageService.store(
                payloadKey(stepExecution.getExecutionLog(), stepExecution.getJobStep(), "output"),
                output
        );
        stepExecution.setOutput(storedOutput.inlineValue());
        stepExecution.setOutputRef(storedOutput.reference());
        markSuccess(stepExecution);
    }

    @Transactional
    public void markFailed(StepExecution stepExecution, Throwable error) {
        stepExecution.setExecutionStatus(JobStatus.FAILED);
        stepExecution.setErrorMessage(error.getMessage());
        complete(stepExecution);
        stepExecutionRepository.save(stepExecution);
    }

    private void complete(StepExecution stepExecution) {
        Instant completedAt = Instant.now();
        stepExecution.setCompletedAt(completedAt);

        if (stepExecution.getStartedAt() != null) {
            stepExecution.setDurationMs(Duration.between(stepExecution.getStartedAt(), completedAt).toMillis());
        }
    }

    private String payloadKey(ExecutionLog executionLog, JobStep jobStep, String direction) {
        return "executions/" + executionLog.getId()
                + "/steps/" + jobStep.getId()
                + "/" + direction + "-" + UUID.randomUUID() + ".json";
    }
}
