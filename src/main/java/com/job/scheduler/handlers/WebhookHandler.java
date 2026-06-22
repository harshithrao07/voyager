package com.job.scheduler.handlers;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.dto.payload.WebhookPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class WebhookHandler implements TaskHandler<WebhookPayload> {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Override
    public StepResult handle(WebhookPayload payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restClient.post()
                .uri(payload.url())
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .body(payload.body() == null ? "{}" : payload.body().toString())
                .retrieve()
                .toEntity(String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Webhook failed with status: " + response.getStatusCode());
        }

        ObjectNode output = objectMapper.createObjectNode();
        output.put("statusCode", response.getStatusCode().value());
        output.set("body", normalizeBody(response.getBody()));
        return new StepResult(output);
    }

    private JsonNode normalizeBody(String body) {
        if (body == null) {
            return objectMapper.nullNode();
        }

        try {
            return objectMapper.readTree(body);
        } catch (Exception ignored) {
            return objectMapper.getNodeFactory().textNode(body);
        }
    }
}
