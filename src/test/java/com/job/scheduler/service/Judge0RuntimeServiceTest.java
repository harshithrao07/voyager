package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionLanguageDTO;
import com.job.scheduler.dto.Judge0LimitsDTO;
import com.job.scheduler.dto.Judge0RuntimeInfoDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Judge0RuntimeServiceTest {
    private final Judge0Client judge0Client = mock(Judge0Client.class);
    private final Judge0RuntimeService service = new Judge0RuntimeService(judge0Client);

    @Test
    void aggregatesRuntimeInfoWhenReachable() {
        when(judge0Client.listLanguages())
                .thenReturn(List.of(new FunctionLanguageDTO(71, "Python (3.8.1)")));
        when(judge0Client.countStatuses()).thenReturn(14);
        when(judge0Client.workerStats())
                .thenReturn(new Judge0Client.WorkerStats(2, 1));
        when(judge0Client.configInfo()).thenReturn(new Judge0LimitsDTO(
                5.0, 15.0, 10.0, 20.0, 128000L, 512000L, 1024, 10240, false, true));

        Judge0RuntimeInfoDTO info = service.runtimeInfo();

        assertThat(info.reachable()).isTrue();
        assertThat(info.error()).isNull();
        assertThat(info.languageCount()).isEqualTo(1);
        assertThat(info.statusCount()).isEqualTo(14);
        assertThat(info.workers()).isEqualTo(2);
        assertThat(info.availableWorkers()).isEqualTo(1);
        assertThat(info.languages()).hasSize(1);
        assertThat(info.limits().maxMemoryLimit()).isEqualTo(512000L);
        assertThat(info.limits().allowEnableNetwork()).isTrue();
    }

    @Test
    void returnsUnreachableWhenJudge0Fails() {
        when(judge0Client.listLanguages())
                .thenThrow(new IllegalStateException("Judge0 returned invalid JSON"));

        Judge0RuntimeInfoDTO info = service.runtimeInfo();

        assertThat(info.reachable()).isFalse();
        assertThat(info.error()).contains("invalid JSON");
        assertThat(info.languages()).isEmpty();
        assertThat(info.limits()).isNull();
    }
}
