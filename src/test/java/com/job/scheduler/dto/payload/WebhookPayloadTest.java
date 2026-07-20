package com.job.scheduler.dto.payload;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory()
            .getValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsSupportedMethodAndCustomHeaders() {
        WebhookPayload payload = new WebhookPayload(
                "https://example.test/hook",
                "PATCH",
                Map.of("Authorization", "Bearer token", "X-Order-ID", "123"),
                objectMapper.createObjectNode().put("ok", true)
        );

        assertThat(validator.validate(payload)).isEmpty();
    }

    @Test
    void rejectsUnsupportedMethodAndHeaderInjection() {
        WebhookPayload payload = new WebhookPayload(
                "https://example.test/hook",
                "TRACE",
                Map.of("Invalid:Name", "value", "X-Test", "safe\r\nInjected: yes"),
                null
        );

        assertThat(validator.validate(payload))
                .extracting(violation -> violation.getMessage())
                .contains(
                        "must be GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS",
                        "must be a valid HTTP header name",
                        "must not contain line breaks"
                );
    }
}
