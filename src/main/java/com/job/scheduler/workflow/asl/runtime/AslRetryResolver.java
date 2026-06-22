package com.job.scheduler.workflow.asl.runtime;

import com.job.scheduler.entity.StateExecutionAttempt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class AslRetryResolver {
    private final AslErrorMatcher errorMatcher;

    public AslRetryResolver(AslErrorMatcher errorMatcher) {
        this.errorMatcher = errorMatcher;
    }

    public RetryDecision resolve(
            JsonNode retry,
            String error,
            List<StateExecutionAttempt> attempts
    ) {
        return resolveFromErrors(
                retry,
                error,
                attempts.stream()
                        .map(StateExecutionAttempt::getError)
                        .filter(Objects::nonNull)
                        .toList()
        );
    }

    /**
     * Same retry semantics as {@link #resolve}, but driven by the list of prior
     * error names instead of Task attempts. Compound states (Parallel, Map)
     * retry without creating Task attempts, so they pass the error of each prior
     * failed generation (including the failure being evaluated now).
     */
    public RetryDecision resolveFromErrors(
            JsonNode retry,
            String error,
            List<String> priorErrors
    ) {
        if (retry == null || !retry.isArray()) {
            return null;
        }
        for (int index = 0; index < retry.size(); index++) {
            JsonNode retrier = retry.get(index);
            if (!errorMatcher.matches(retrier.get("ErrorEquals"), error)) {
                continue;
            }
            int selectedIndex = index;

            long matchingFailures = priorErrors.stream()
                    .filter(priorError -> selectedRetrierIndex(
                            retry,
                            priorError
                    ) == selectedIndex)
                    .count();
            int maximumAttempts = retrier.path("MaxAttempts").asInt(3);
            if (matchingFailures > maximumAttempts) {
                return null;
            }

            double intervalSeconds =
                    retrier.path("IntervalSeconds").asDouble(1);
            double backoffRate = retrier.path("BackoffRate").asDouble(2.0);
            double delaySeconds = intervalSeconds * Math.pow(
                    backoffRate,
                    matchingFailures - 1
            );
            if (retrier.has("MaxDelaySeconds")) {
                delaySeconds = Math.min(
                        delaySeconds,
                        retrier.get("MaxDelaySeconds").asDouble()
                );
            }
            if (retrier.has("JitterStrategy")) {
                String jitterStrategy =
                        retrier.get("JitterStrategy").stringValue();
                if (!"FULL".equals(jitterStrategy)) {
                    throw new IllegalStateException(
                            "Unsupported ASL JitterStrategy: "
                                    + jitterStrategy
                    );
                }
                delaySeconds = ThreadLocalRandom.current().nextDouble(
                        delaySeconds
                );
            }
            return new RetryDecision(
                    selectedIndex,
                    Duration.ofMillis(Math.max(
                            0L,
                            Math.round(delaySeconds * 1000)
                    ))
            );
        }
        return null;
    }

    private int selectedRetrierIndex(JsonNode retry, String error) {
        for (int index = 0; index < retry.size(); index++) {
            if (errorMatcher.matches(
                    retry.get(index).get("ErrorEquals"),
                    error
            )) {
                return index;
            }
        }
        return -1;
    }

    public record RetryDecision(int retrierIndex, Duration delay) {
    }
}
