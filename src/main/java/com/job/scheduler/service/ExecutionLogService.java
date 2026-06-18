package com.job.scheduler.service;

import com.job.scheduler.entity.ExecutionLog;
import com.job.scheduler.enums.JobStatus;
import com.job.scheduler.repository.ExecutionLogRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ExecutionLogService {
    private final ExecutionLogRepository executionLogRepository;

    @Transactional
    public void updateExecutionStatus(
            ExecutionLog executionLog,
            JobStatus jobStatus,
            String errorMessage,
            String workerId
    ) {
        executionLog.setExecutionStatus(jobStatus);
        executionLog.setWorkerId(workerId);

        switch (jobStatus) {
            case RUNNING -> {
                executionLog.setStartedAt(Instant.now());
                executionLog.setCompletedAt(null);
                executionLog.setDurationMs(null);
                executionLog.setErrorMessage(null);
            }

            case FAILED, SUCCESS -> {
                executionLog.setErrorMessage(jobStatus == JobStatus.FAILED ? errorMessage : null);

                Instant completedAt = Instant.now();
                executionLog.setCompletedAt(completedAt);

                if (executionLog.getStartedAt() != null) {
                    executionLog.setDurationMs(
                            Duration.between(executionLog.getStartedAt(), completedAt).toMillis()
                    );
                }
            }

            case PENDING, QUEUED, CANCELED, DEAD -> {
                // No additional execution-log timestamps are updated for these states.
            }
        }

        executionLogRepository.save(executionLog);
    }

}
