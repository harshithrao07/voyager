package com.job.scheduler.config;

import com.job.scheduler.dto.FunctionLanguageDTO;
import com.job.scheduler.service.Judge0Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Judge0HealthIndicatorTest {
    private final Judge0Client judge0Client = mock(Judge0Client.class);
    private final HealthIndicator indicator =
            new HealthIndicatorConfig().judge0HealthIndicator(judge0Client);

    @Test
    void upWhenLanguagesAndWorkersAvailable() {
        when(judge0Client.listLanguages())
                .thenReturn(List.of(new FunctionLanguageDTO(71, "Python", true)));
        when(judge0Client.countStatuses()).thenReturn(14);
        when(judge0Client.workerStats())
                .thenReturn(new Judge0Client.WorkerStats(2, 1));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("languages", 1)
                .containsEntry("statuses", 14)
                .containsEntry("workers", 2)
                .containsEntry("availableWorkers", 1);
    }

    @Test
    void downWhenNoWorkers() {
        when(judge0Client.listLanguages())
                .thenReturn(List.of(new FunctionLanguageDTO(71, "Python", true)));
        when(judge0Client.countStatuses()).thenReturn(14);
        when(judge0Client.workerStats())
                .thenReturn(new Judge0Client.WorkerStats(0, 0));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void downWhenClientUnreachable() {
        when(judge0Client.listLanguages())
                .thenThrow(new IllegalStateException("Judge0 unreachable"));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
