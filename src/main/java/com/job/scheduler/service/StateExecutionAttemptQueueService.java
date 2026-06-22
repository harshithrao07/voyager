package com.job.scheduler.service;

import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StateExecutionAttemptQueueService {
    private final StateExecutionAttemptRepository attemptRepository;

    /**
     * A failed producer future does not prove that Kafka rejected the record:
     * the broker may have stored it while the acknowledgement was lost. Keep
     * the attempt QUEUED so a delivered copy can still claim it, and let the
     * stale-queue watchdog make it dispatchable again after the safety window.
     */
    @Transactional
    public void recordDispatchUncertain(UUID attemptId, String error) {
        StateExecutionAttempt attempt = findForUpdate(attemptId);
        if (attempt.getStatus() != StateExecutionAttemptStatus.QUEUED) {
            return;
        }
        attempt.setLastDispatchError(error);
        attemptRepository.save(attempt);
    }

    @Transactional
    public boolean claimForExecution(UUID attemptId, String workerId) {
        Instant now = Instant.now();
        boolean claimed = attemptRepository.claimQueuedAttemptForExecution(
                attemptId,
                workerId,
                now,
                StateExecutionAttemptStatus.QUEUED,
                StateExecutionAttemptStatus.RUNNING
        ) == 1;
        if (!claimed) {
            return false;
        }
        StateExecutionAttempt attempt = findForUpdate(attemptId);
        attempt.setTimeoutAt(deadline(now, attempt.getTimeoutSeconds()));
        attempt.setHeartbeatDeadlineAt(
                deadline(now, attempt.getHeartbeatSeconds())
        );
        attemptRepository.save(attempt);
        return true;
    }

    @Transactional
    public boolean recordHeartbeat(UUID attemptId, String workerId) {
        StateExecutionAttempt attempt = attemptRepository.findById(attemptId)
                .orElse(null);
        if (attempt == null || attempt.getHeartbeatSeconds() == null) {
            return false;
        }
        Instant now = Instant.now();
        return attemptRepository.recordHeartbeat(
                attemptId,
                workerId,
                now,
                now.plusSeconds(attempt.getHeartbeatSeconds()),
                StateExecutionAttemptStatus.RUNNING
        ) == 1;
    }

    private StateExecutionAttempt findForUpdate(UUID attemptId) {
        return attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "State execution attempt does not exist"
                ));
    }

    private Instant deadline(Instant startedAt, Long seconds) {
        return seconds == null ? null : startedAt.plusSeconds(seconds);
    }
}
