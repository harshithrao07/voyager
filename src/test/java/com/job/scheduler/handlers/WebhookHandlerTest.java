package com.job.scheduler.handlers;

import com.job.scheduler.dto.payload.WebhookPayload;
import com.job.scheduler.dto.StepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookHandlerTest {

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private WebhookHandler webhookHandler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        webhookHandler = new WebhookHandler(restClient, objectMapper);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(String.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    void handlePostsJsonBodyToWebhook() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("message", "ping");
        WebhookPayload payload = new WebhookPayload("https://example.com/hook", body);

        when(responseSpec.toEntity(String.class)).thenReturn(ResponseEntity.ok("ok"));

        StepResult result = webhookHandler.handle(payload);

        verify(restClient).post();
        verify(requestBodyUriSpec).uri("https://example.com/hook");
        verify(requestBodySpec).headers(any(Consumer.class));
        verify(requestBodySpec).body(body.toString());
        verify(responseSpec).toEntity(String.class);
        assertThat(result.output().get("statusCode").intValue()).isEqualTo(200);
        assertThat(result.output().get("body").stringValue()).isEqualTo("ok");
    }

    @Test
    void handleUsesEmptyJsonWhenBodyIsNull() {
        WebhookPayload payload = new WebhookPayload("https://example.com/hook", null);

        when(responseSpec.toEntity(String.class)).thenReturn(ResponseEntity.ok("ok"));

        StepResult result = webhookHandler.handle(payload);

        verify(requestBodySpec).body("{}");
        assertThat(result.output().get("statusCode").intValue()).isEqualTo(200);
        assertThat(result.output().get("body").stringValue()).isEqualTo("ok");
    }

    @Test
    void handleKeepsJsonResponseBodyStructured() {
        WebhookPayload payload = new WebhookPayload("https://example.com/hook", null);
        when(responseSpec.toEntity(String.class))
                .thenReturn(ResponseEntity.ok("{\"accepted\":true}"));

        StepResult result = webhookHandler.handle(payload);

        assertThat(result.output().get("body").get("accepted").booleanValue()).isTrue();
    }

    @Test
    void handleThrowsWhenWebhookResponseIsNotSuccessful() {
        WebhookPayload payload = new WebhookPayload("https://example.com/hook", null);

        when(responseSpec.toEntity(String.class))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_REQUEST).body("bad request"));

        assertThatThrownBy(() -> webhookHandler.handle(payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Webhook failed with status");
    }
}
