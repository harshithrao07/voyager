package com.job.scheduler.scheduler;

import com.job.scheduler.service.WorkflowExecutionRetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionRetentionSchedulerServiceTest {
    private static final long RETENTION_AGE_MS = 60_000;

    @Mock
    private WorkflowExecutionRetentionService retentionService;

    private WorkflowExecutionRetentionSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WorkflowExecutionRetentionSchedulerService(
                retentionService
        );
        ReflectionTestUtils.setField(
                scheduler,
                "retentionAgeMs",
                RETENTION_AGE_MS
        );
        ReflectionTestUtils.setField(scheduler, "batchSize", 25);
    }

    @Test
    void deletesOneExpiredBatchUsingConfiguredAgeAndSize() {
        when(retentionService.deleteCompletedBefore(
                any(Instant.class),
                eq(25)
        )).thenReturn(
                WorkflowExecutionRetentionService.RetentionResult.empty()
        );
        Instant earliestCutoff = Instant.now()
                .minusMillis(RETENTION_AGE_MS + 1_000);
        Instant latestCutoff = Instant.now()
                .minusMillis(RETENTION_AGE_MS - 1_000);

        scheduler.deleteExpiredExecutions();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor
                .forClass(Instant.class);
        verify(retentionService).deleteCompletedBefore(
                cutoff.capture(),
                eq(25)
        );
        assertThat(cutoff.getValue())
                .isBetween(earliestCutoff, latestCutoff);
    }

    @Test
    void rejectsUnsafeRetentionConfiguration() {
        ReflectionTestUtils.setField(scheduler, "retentionAgeMs", 0L);

        assertThatThrownBy(scheduler::deleteExpiredExecutions)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention age");
    }
}
