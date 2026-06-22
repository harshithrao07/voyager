package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.entity.StateExecutionAttempt;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AslRetryResolverTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AslRetryResolver resolver =
            new AslRetryResolver(new AslErrorMatcher());

    @Test
    void usesFirstMatchingRetrierAndAppliesExponentialBackoff() {
        var retry = objectMapper.readTree("""
                [
                  {
                    "ErrorEquals": ["Temporary"],
                    "IntervalSeconds": 2,
                    "MaxAttempts": 3,
                    "BackoffRate": 3
                  },
                  {
                    "ErrorEquals": ["States.ALL"],
                    "IntervalSeconds": 10
                  }
                ]
                """);

        var decision = resolver.resolve(
                retry,
                "Temporary",
                List.of(failed("Temporary"), failed("Temporary"))
        );

        assertThat(decision.retrierIndex()).isZero();
        assertThat(decision.delay()).isEqualTo(java.time.Duration.ofSeconds(6));
    }

    @Test
    void stopsRetryingAfterMaxAttemptsAndExcludesTimeoutFromTaskFailed() {
        var retry = objectMapper.readTree("""
                [
                  {
                    "ErrorEquals": ["States.TaskFailed"],
                    "MaxAttempts": 1
                  }
                ]
                """);

        assertThat(resolver.resolve(
                retry,
                "Application.Error",
                List.of(failed("Application.Error"), failed("Application.Error"))
        )).isNull();
        assertThat(resolver.resolve(
                retry,
                "States.Timeout",
                List.of(failed("States.Timeout"))
        )).isNull();
    }

    private StateExecutionAttempt failed(String error) {
        StateExecutionAttempt attempt = new StateExecutionAttempt();
        attempt.setError(error);
        return attempt;
    }
}
