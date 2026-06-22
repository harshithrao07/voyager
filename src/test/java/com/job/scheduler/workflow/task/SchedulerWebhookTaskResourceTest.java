package com.job.scheduler.workflow.task;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.dto.payload.WebhookPayload;
import com.job.scheduler.handlers.WebhookHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulerWebhookTaskResourceTest {
    @Mock
    private WebhookHandler webhookHandler;
    @Mock
    private TaskPayloadMapper payloadMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SchedulerWebhookTaskResource resource;
    private final URI uri = URI.create("scheduler://webhook");

    @BeforeEach
    void setUp() {
        resource = new SchedulerWebhookTaskResource(
                webhookHandler, payloadMapper);
        org.mockito.Mockito.lenient().when(payloadMapper.bind(any(), any()))
                .thenReturn(new WebhookPayload("https://h", null));
    }

    @Test
    void supportsSchedulerWebhookOnly() {
        assertThat(resource.supports(uri)).isTrue();
        assertThat(resource.supports(URI.create("scheduler://send-email")))
                .isFalse();
        assertThat(resource.supports(URI.create("mcp://s/t"))).isFalse();
    }

    @Test
    void returnsHandlerOutputOnSuccess() {
        JsonNode out = objectMapper.createObjectNode().put("statusCode", 202);
        when(webhookHandler.handle(any())).thenReturn(new StepResult(out));

        assertThat(resource.execute(uri, objectMapper.createObjectNode()))
                .isEqualTo(out);
    }

    @Test
    void mapsForbiddenToPermissionsWithDetail() {
        when(webhookHandler.handle(any())).thenThrow(
                HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN, "Forbidden",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "denied".getBytes(), null));

        TaskResourceException exception = catchThrowableOfType(
                TaskResourceException.class,
                () -> resource.execute(uri, objectMapper.createObjectNode()));

        assertThat(exception.error()).isEqualTo(TaskResourceErrors.PERMISSIONS);
        assertThat(exception.detail().get("statusCode").intValue())
                .isEqualTo(403);
        assertThat(exception.detail().get("body").stringValue())
                .contains("denied");
    }

    @Test
    void mapsOther4xxToClientError() {
        when(webhookHandler.handle(any())).thenThrow(
                HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], null));

        assertThatThrownBy(() ->
                resource.execute(uri, objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.WEBHOOK_CLIENT_ERROR);
    }

    @Test
    void maps5xxToServerError() {
        when(webhookHandler.handle(any())).thenThrow(
                HttpServerErrorException.create(
                        HttpStatus.BAD_GATEWAY, "Bad Gateway",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], null));

        assertThatThrownBy(() ->
                resource.execute(uri, objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.WEBHOOK_SERVER_ERROR);
    }

    @Test
    void mapsConnectionFailureToTimeout() {
        when(webhookHandler.handle(any()))
                .thenThrow(new ResourceAccessException("connect timed out"));

        assertThatThrownBy(() ->
                resource.execute(uri, objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.WEBHOOK_TIMEOUT);
    }
}
