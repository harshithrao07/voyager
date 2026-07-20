package com.job.scheduler.handlers;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.dto.payload.WebhookPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WebhookHandler implements TaskHandler<WebhookPayload> {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Override
    public StepResult handle(WebhookPayload payload) {
        HttpMethod method = payload.method() == null || payload.method().isBlank()
                ? HttpMethod.POST
                : HttpMethod.valueOf(payload.method().trim().toUpperCase(Locale.ROOT));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (Map.Entry<String, String> header : payload.headers() == null
                ? Map.<String, String>of().entrySet()
                : payload.headers().entrySet()) {
            headers.set(header.getKey(), header.getValue());
        }

        RestClient.RequestBodySpec request = restClient.method(method)
                .uri(payload.url())
                .headers(httpHeaders -> httpHeaders.addAll(headers));

        if (payload.body() != null) {
            request = request.body(payload.body().toString());
        } else if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            // Preserve the original webhook behavior for body-oriented methods.
            request = request.body("{}");
        }

        ResponseEntity<String> response = request.retrieve()
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
