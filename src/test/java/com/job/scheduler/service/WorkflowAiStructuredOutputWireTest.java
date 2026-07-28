package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowAiStructuredOutputWireTest {
    private static final String KEY = "xYZP4KD/T0APHH/9GMLiO9vt8D/GFCJXwLzh5ALiGV0=";

    @Test
    void sendsTheOpenAiStrictJsonSchemaEnvelopeOnTheWire() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] body = """
                    {
                      "id":"completion-1",
                      "object":"chat.completion",
                      "created":1,
                      "model":"wire-model",
                      "choices":[{
                        "index":0,
                        "message":{"role":"assistant","content":"{\\"stage\\":\\"COLLECTING_WORKFLOW_DETAILS\\",\\"message\\":\\"hi\\"}"},
                        "finish_reason":"stop"
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiModelConfig config = new AiModelConfig();
            config.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_LOCAL);
            config.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1");
            config.setModelName("wire-model");

            ChatModel model = new WorkflowAiModelResolver(new SecretCipher(KEY)).resolve(config);
            model.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from("hello")))
                    .responseFormat(WorkflowAiResponseSchema.responseFormat())
                    .build());

            JsonNode sent = new ObjectMapper().readTree(requestBody.get());
            JsonNode responseFormat = sent.path("response_format");
            assertThat(responseFormat.path("type").asText()).isEqualTo("json_schema");
            assertThat(responseFormat.path("json_schema").path("name").asText())
                    .isEqualTo("voyager_workflow_ai_response");
            assertThat(responseFormat.path("json_schema").path("strict").asBoolean()).isTrue();
            assertThat(responseFormat.path("json_schema").path("schema")
                    .path("additionalProperties").asBoolean()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    /**
     * Reproduces a Cloudflare Workers AI response where {@code message.content} is inlined as a JSON
     * object instead of a string. The OpenAI client fails to deserialize it, and the thrown exception
     * must carry the substrings {@code WorkflowAiConversationService} keys on to trigger the
     * structured-output downgrade instead of failing the turn outright.
     */
    @Test
    void objectContentResponseThrowsTheDetectableDeserializationFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = """
                    {
                      "id":"completion-1",
                      "object":"chat.completion",
                      "created":1,
                      "model":"wire-model",
                      "choices":[{
                        "index":0,
                        "message":{"role":"assistant","content":{"stage":"COLLECTING_WORKFLOW_DETAILS","message":"hi"}},
                        "finish_reason":"stop"
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiModelConfig config = new AiModelConfig();
            config.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_API);
            config.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1");
            config.setModelName("wire-model");

            ChatModel model = new WorkflowAiModelResolver(new SecretCipher(KEY)).resolve(config);

            RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class,
                    () -> model.chat(ChatRequest.builder()
                            .messages(List.of(UserMessage.from("hello")))
                            .responseFormat(WorkflowAiResponseSchema.responseFormat())
                            .build())
            );

            String combined = combinedMessages(failure).toLowerCase(java.util.Locale.ROOT);
            assertThat(combined).contains("cannot deserialize value of type");
            assertThat(combined).contains("java.lang.string");
            assertThat(combined).contains("from object value");
            assertThat(combined).contains("content");
        } finally {
            server.stop(0);
        }
    }

    private static String combinedMessages(Throwable failure) {
        StringBuilder combined = new StringBuilder();
        for (Throwable current = failure; current != null;
             current = current.getCause() == current ? null : current.getCause()) {
            if (current.getMessage() != null) {
                combined.append(current.getMessage()).append('\n');
            }
        }
        return combined.toString();
    }
}
