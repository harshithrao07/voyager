package com.job.scheduler.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TaskAttemptHeartbeatServiceTest {
    private TaskAttemptHeartbeatService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void rejectsNonPositiveMaximumPulseInterval() {
        assertThatThrownBy(() -> new TaskAttemptHeartbeatService(
                mock(StateExecutionAttemptQueueService.class),
                0
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void heartbeatDisabledReturnsNoopLease() {
        StateExecutionAttemptQueueService queueService =
                mock(StateExecutionAttemptQueueService.class);
        service = new TaskAttemptHeartbeatService(queueService, 100);
        UUID attemptId = UUID.randomUUID();

        try (TaskAttemptHeartbeatService.HeartbeatLease ignored =
                     service.start(attemptId, "worker-1", null)) {
            // No heartbeat is scheduled.
        }

        verify(queueService, after(150).never())
                .recordHeartbeat(attemptId, "worker-1");
    }

    @Test
    void pulsesAndLeaseCancellationStopsFutureHeartbeats()
            throws Exception {
        StateExecutionAttemptQueueService queueService =
                mock(StateExecutionAttemptQueueService.class);
        service = new TaskAttemptHeartbeatService(queueService, 100);
        UUID attemptId = UUID.randomUUID();

        TaskAttemptHeartbeatService.HeartbeatLease lease =
                service.start(attemptId, "worker-1", 10L);
        verify(queueService, org.mockito.Mockito.timeout(500).atLeastOnce())
                .recordHeartbeat(attemptId, "worker-1");

        lease.close();
        Thread.sleep(50);
        org.mockito.Mockito.clearInvocations(queueService);

        verify(queueService, after(250).never())
                .recordHeartbeat(attemptId, "worker-1");
    }
}
