package com.job.scheduler.scheduler;

import com.job.scheduler.repository.StateExecutionAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QueuedTaskAttemptWatchdogServiceTest {
    @Mock
    private StateExecutionAttemptRepository attemptRepository;

    private QueuedTaskAttemptWatchdogService watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new QueuedTaskAttemptWatchdogService(attemptRepository);
        ReflectionTestUtils.setField(watchdog, "queuedTimeoutMs", 300000L);
        ReflectionTestUtils.setField(watchdog, "recoveryLimit", 25);
    }

    @Test
    void recoversStaleQueuedAttempts() {
        watchdog.recoverStaleQueuedAttempts();

        verify(attemptRepository).recoverStaleQueuedAttempts(
                any(Instant.class),
                any(Instant.class),
                eq(25)
        );
    }
}
