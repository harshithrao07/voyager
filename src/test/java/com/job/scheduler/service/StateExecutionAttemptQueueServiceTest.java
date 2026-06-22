package com.job.scheduler.service;

import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class StateExecutionAttemptQueueServiceTest {
    @Mock
    private StateExecutionAttemptRepository attemptRepository;

    @Test
    void recordsUncertainDispatchWithoutMakingItImmediatelyDispatchable() {
        StateExecutionAttempt attempt = attempt(
                StateExecutionAttemptStatus.QUEUED
        );
        Instant queuedAt = Instant.now();
        attempt.setQueuedAt(queuedAt);
        when(attemptRepository.findByIdForUpdate(attempt.getId()))
                .thenReturn(Optional.of(attempt));
        var service = new StateExecutionAttemptQueueService(attemptRepository);

        service.recordDispatchUncertain(
                attempt.getId(),
                "Producer acknowledgement lost"
        );

        assertThat(attempt.getStatus())
                .isEqualTo(StateExecutionAttemptStatus.QUEUED);
        assertThat(attempt.getQueuedAt()).isEqualTo(queuedAt);
        assertThat(attempt.getLastDispatchError())
                .isEqualTo("Producer acknowledgement lost");
        verify(attemptRepository).save(attempt);
    }

    @Test
    void uncertainCallbackCannotMoveClaimedWorkerBackToQueue() {
        StateExecutionAttempt attempt = attempt(
                StateExecutionAttemptStatus.RUNNING
        );
        when(attemptRepository.findByIdForUpdate(attempt.getId()))
                .thenReturn(Optional.of(attempt));
        var service = new StateExecutionAttemptQueueService(attemptRepository);

        service.recordDispatchUncertain(
                attempt.getId(),
                "Late producer failure"
        );

        assertThat(attempt.getStatus())
                .isEqualTo(StateExecutionAttemptStatus.RUNNING);
        verify(attemptRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void claimsQueuedAttemptForOneWorkerAtomically() {
        UUID attemptId = UUID.randomUUID();
        StateExecutionAttempt attempt = attempt(
                StateExecutionAttemptStatus.RUNNING
        );
        attempt.setId(attemptId);
        attempt.setTimeoutSeconds(30L);
        attempt.setHeartbeatSeconds(10L);
        when(attemptRepository.claimQueuedAttemptForExecution(
                org.mockito.ArgumentMatchers.eq(attemptId),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.eq(
                        StateExecutionAttemptStatus.QUEUED
                ),
                org.mockito.ArgumentMatchers.eq(
                        StateExecutionAttemptStatus.RUNNING
                )
        )).thenReturn(1);
        when(attemptRepository.findByIdForUpdate(attemptId))
                .thenReturn(Optional.of(attempt));
        var service = new StateExecutionAttemptQueueService(attemptRepository);

        assertThat(service.claimForExecution(attemptId, "worker-1")).isTrue();
        assertThat(attempt.getTimeoutAt()).isNotNull();
        assertThat(attempt.getHeartbeatDeadlineAt()).isNotNull();
        assertThat(attempt.getTimeoutAt().minusSeconds(20))
                .isEqualTo(attempt.getHeartbeatDeadlineAt());
        verify(attemptRepository).save(attempt);
    }

    @Test
    void duplicateWorkerClaimIsRejected() {
        UUID attemptId = UUID.randomUUID();
        when(attemptRepository.claimQueuedAttemptForExecution(
                org.mockito.ArgumentMatchers.eq(attemptId),
                org.mockito.ArgumentMatchers.eq("worker-2"),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.eq(
                        StateExecutionAttemptStatus.QUEUED
                ),
                org.mockito.ArgumentMatchers.eq(
                        StateExecutionAttemptStatus.RUNNING
                )
        )).thenReturn(0);
        var service = new StateExecutionAttemptQueueService(attemptRepository);

        assertThat(service.claimForExecution(attemptId, "worker-2")).isFalse();
    }

    @Test
    void heartbeatExtendsDeadlineOnlyForOwningRunningWorker() {
        StateExecutionAttempt attempt = attempt(
                StateExecutionAttemptStatus.RUNNING
        );
        attempt.setHeartbeatSeconds(4L);
        when(attemptRepository.findById(attempt.getId()))
                .thenReturn(Optional.of(attempt));
        when(attemptRepository.recordHeartbeat(
                eq(attempt.getId()),
                eq("worker-1"),
                any(Instant.class),
                any(Instant.class),
                eq(StateExecutionAttemptStatus.RUNNING)
        )).thenReturn(1);
        var service = new StateExecutionAttemptQueueService(attemptRepository);

        assertThat(service.recordHeartbeat(
                attempt.getId(),
                "worker-1"
        )).isTrue();

        verify(attemptRepository).recordHeartbeat(
                eq(attempt.getId()),
                eq("worker-1"),
                any(Instant.class),
                any(Instant.class),
                eq(StateExecutionAttemptStatus.RUNNING)
        );
    }

    private StateExecutionAttempt attempt(
            StateExecutionAttemptStatus status
    ) {
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setStatus(status);
        return attempt;
    }
}
