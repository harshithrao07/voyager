package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowPayloadLimitsTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void measuresSerializedPayloadAsUtf8Bytes() {
        WorkflowPayloadLimits limits = limits(12);

        assertThatThrownBy(() -> limits.serialize(
                objectMapper.createObjectNode().put("v", "€€"),
                WorkflowPayloadLimits.Kind.INPUT
        ))
                .isInstanceOf(WorkflowPayloadLimitExceededException.class)
                .hasMessageContaining("UTF-8 bytes");
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThatThrownBy(() -> new WorkflowPayloadLimits(
                objectMapper,
                0,
                10,
                10,
                10,
                10,
                10
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-input-bytes");
    }

    private WorkflowPayloadLimits limits(long bytes) {
        return new WorkflowPayloadLimits(
                objectMapper,
                bytes,
                bytes,
                bytes,
                bytes,
                bytes,
                32
        );
    }
}
