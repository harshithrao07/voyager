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

@Service
@RequiredArgsConstructor
public class StepExecutionService {
    private final StepExecutionRepository stepExecutionRepository;

    public StepExecution start(ExecutionLog executionLog, JobStep jobStep) {
        StepExecution stepExecution = new StepExecution();
        stepExecution.setExecutionLog(executionLog);
        stepExecution.setJobStep(jobStep);
        stepExecution.setStepOrder(jobStep.getStepOrder());
        stepExecution.setStepType(jobStep.getStepType());
        stepExecution.setExecutionStatus(JobStatus.RUNNING);
        stepExecution.setStartedAt(Instant.now());

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
}
