package com.job.scheduler.scheduler;

import com.job.scheduler.dto.WorkflowTaskDispatchEvent;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.producers.WorkflowTaskQueueProducer;
import com.job.scheduler.repository.StateExecutionAttemptRepository;
import com.job.scheduler.service.StateExecutionAttemptQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DueTaskAttemptSchedulerServiceTest {
    @Mock
    private StateExecutionAttemptRepository attemptRepository;
    @Mock
    private WorkflowTaskQueueProducer queueProducer;
    @Mock
    private StateExecutionAttemptQueueService queueService;

    private DueTaskAttemptSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DueTaskAttemptSchedulerService(
                attemptRepository,
                queueProducer,
                queueService
        );
        ReflectionTestUtils.setField(scheduler, "claimLimit", 25);
    }

    @Test
    void publishesClaimedAttemptId() {
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setId(UUID.randomUUID());
        when(attemptRepository.claimDueAttemptsForDispatch(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(attempt));
        when(queueProducer.send(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        scheduler.dispatchDueAttempts();

        verify(queueProducer).send(
                new WorkflowTaskDispatchEvent(attempt.getId())
        );
    }

    @Test
    void keepsAttemptQueuedWhenKafkaPublicationIsUncertain() {
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setId(UUID.randomUUID());
        when(attemptRepository.claimDueAttemptsForDispatch(
                any(Instant.class),
                eq(25)
        )).thenReturn(List.of(attempt));
        when(queueProducer.send(any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("Kafka unavailable")
                ));

        scheduler.dispatchDueAttempts();

        verify(queueService).recordDispatchUncertain(
                eq(attempt.getId()),
                eq("Kafka unavailable")
        );
    }
}
