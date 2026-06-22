package com.job.scheduler.workflow.asl.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Component
public class WorkflowPayloadLimits {
    public static final long DEFAULT_JSON_BYTES = 262_144L;
    public static final long DEFAULT_ERROR_BYTES = 32_768L;

    private final ObjectMapper objectMapper;
    private final long inputBytes;
    private final long outputBytes;
    private final long variablesBytes;
    private final long taskArgumentsBytes;
    private final long taskResultBytes;
    private final long errorDetailsBytes;

    public WorkflowPayloadLimits(
            ObjectMapper objectMapper,
            @Value("${scheduler.workflow.max-input-bytes:262144}")
            long inputBytes,
            @Value("${scheduler.workflow.max-output-bytes:262144}")
            long outputBytes,
            @Value("${scheduler.workflow.max-variables-bytes:262144}")
            long variablesBytes,
            @Value("${scheduler.workflow.max-task-arguments-bytes:262144}")
            long taskArgumentsBytes,
            @Value("${scheduler.workflow.max-task-result-bytes:262144}")
            long taskResultBytes,
            @Value("${scheduler.workflow.max-error-details-bytes:32768}")
            long errorDetailsBytes
    ) {
        this.objectMapper = objectMapper;
        this.inputBytes = positive(inputBytes, "max-input-bytes");
        this.outputBytes = positive(outputBytes, "max-output-bytes");
        this.variablesBytes = positive(variablesBytes, "max-variables-bytes");
        this.taskArgumentsBytes = positive(
                taskArgumentsBytes,
                "max-task-arguments-bytes"
        );
        this.taskResultBytes = positive(
                taskResultBytes,
                "max-task-result-bytes"
        );
        this.errorDetailsBytes = atLeast(
                errorDetailsBytes,
                32,
                "max-error-details-bytes"
        );
    }

    public static WorkflowPayloadLimits defaults(ObjectMapper objectMapper) {
        return new WorkflowPayloadLimits(
                objectMapper,
                DEFAULT_JSON_BYTES,
                DEFAULT_JSON_BYTES,
                DEFAULT_JSON_BYTES,
                DEFAULT_JSON_BYTES,
                DEFAULT_JSON_BYTES,
                DEFAULT_ERROR_BYTES
        );
    }

    public String serialize(JsonNode value, Kind kind) {
        try {
            String serialized = objectMapper.writeValueAsString(value);
            validate(serialized, kind);
            return serialized;
        } catch (WorkflowPayloadLimitExceededException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not serialize " + kind.label(),
                    exception
            );
        }
    }

    public void validate(String value, Kind kind) {
        if (value == null) {
            return;
        }
        long actual = value.getBytes(StandardCharsets.UTF_8).length;
        long maximum = maximum(kind);
        if (actual > maximum) {
            throw new WorkflowPayloadLimitExceededException(
                    kind,
                    actual,
                    maximum
            );
        }
    }

    public boolean exceedsErrorLimit(String value) {
        return value != null
                && value.getBytes(StandardCharsets.UTF_8).length
                > errorDetailsBytes;
    }

    public String fitErrorDetail(String value) {
        if (value == null || !exceedsErrorLimit(value)) {
            return value;
        }
        int end = value.length();
        while (end > 0) {
            String candidate = value.substring(0, end);
            if (candidate.getBytes(StandardCharsets.UTF_8).length
                    <= errorDetailsBytes) {
                return candidate;
            }
            end = value.offsetByCodePoints(end, -1);
        }
        return "";
    }

    private long maximum(Kind kind) {
        return switch (kind) {
            case INPUT -> inputBytes;
            case OUTPUT -> outputBytes;
            case VARIABLES -> variablesBytes;
            case TASK_ARGUMENTS -> taskArgumentsBytes;
            case TASK_RESULT -> taskResultBytes;
            case ERROR_DETAILS -> errorDetailsBytes;
        };
    }

    private long positive(long value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "scheduler.workflow." + property + " must be positive"
            );
        }
        return value;
    }

    private long atLeast(long value, long minimum, String property) {
        if (value < minimum) {
            throw new IllegalArgumentException(
                    "scheduler.workflow." + property
                            + " must be at least " + minimum
            );
        }
        return value;
    }

    public enum Kind {
        INPUT("workflow or state input"),
        OUTPUT("workflow or state output"),
        VARIABLES("workflow variables"),
        TASK_ARGUMENTS("Task arguments"),
        TASK_RESULT("Task result"),
        ERROR_DETAILS("error details");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
