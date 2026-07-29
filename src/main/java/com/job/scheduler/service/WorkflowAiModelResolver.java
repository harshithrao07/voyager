package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.AiStructuredOutputMode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class WorkflowAiModelResolver {
    private final SecretCipher secretCipher;

    // Reasoning-capable local models emit a <think> block before the JSON; a long proposal
    // (function sourceCode + MCP requirements) can otherwise be cut off.
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(150);
    private final ThreadLocal<Duration> requestTimeoutOverride = new ThreadLocal<>();

    public <T> T withRequestTimeout(Duration timeout, Supplier<T> operation) {
        Duration previous = requestTimeoutOverride.get();
        requestTimeoutOverride.set(timeout);
        try {
            return operation.get();
        } finally {
            if (previous == null) {
                requestTimeoutOverride.remove();
            } else {
                requestTimeoutOverride.set(previous);
            }
        }
    }

    private Duration requestTimeout() {
        Duration override = requestTimeoutOverride.get();
        return override == null ? REQUEST_TIMEOUT : override;
    }

    public ChatModel resolve(AiModelConfig config) {
        return resolve(config, true);
    }

    /**
     * Builds the same OpenAI-compatible client without strict-schema signaling.
     *
     * <p>Some compatible providers accept {@code json_schema} but reject OpenAI's {@code strict}
     * flag. The conversation service uses this client only after that exact rejection.
     */
    public ChatModel resolveNonStrict(AiModelConfig config) {
        return resolve(config, false);
    }

    private ChatModel resolve(AiModelConfig config, boolean strictJsonSchema) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(credential(config))
                .modelName(config.getModelName())
                .strictJsonSchema(strictJsonSchema)
                .timeout(requestTimeout())
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * Process-local mirror of the persisted structured-output capability.
     *
     * <p>Entity changes are normally persisted with the conversation transaction. The mirror also
     * prevents repeated capability probes in the same process if a later provider failure rolls that
     * transaction back.
     */
    private final Map<String, AiStructuredOutputMode> structuredOutputModes =
            new ConcurrentHashMap<>();

    public AiStructuredOutputMode structuredOutputMode(AiModelConfig config) {
        AiStructuredOutputMode persisted = config.getStructuredOutputMode() == null
                ? AiStructuredOutputMode.UNKNOWN
                : config.getStructuredOutputMode();
        return structuredOutputModes.computeIfAbsent(endpointKey(config), ignored -> persisted);
    }

    /**
     * Returns the strongest mode worth trying for this endpoint.
     *
     * <p>Cloudflare's native schema mode is non-strict; other OpenAI-compatible endpoints first get
     * the standard strict envelope. Once a mode is learned, endpoint detection is no longer involved.
     */
    public AiStructuredOutputMode preferredStructuredOutputMode(AiModelConfig config) {
        AiStructuredOutputMode learned = structuredOutputMode(config);
        if (learned != AiStructuredOutputMode.UNKNOWN) {
            return learned;
        }
        String baseUrl = config.getBaseUrl() == null ? "" : config.getBaseUrl().toLowerCase();
        return baseUrl.contains("api.cloudflare.com")
                ? AiStructuredOutputMode.JSON_SCHEMA
                : AiStructuredOutputMode.STRICT_JSON_SCHEMA;
    }

    public void recordStructuredOutputMode(
            AiModelConfig config,
            AiStructuredOutputMode mode
    ) {
        if (config == null || mode == null || mode == AiStructuredOutputMode.UNKNOWN) {
            return;
        }
        structuredOutputModes.put(endpointKey(config), mode);
        if (config.getStructuredOutputMode() != mode) {
            config.setStructuredOutputMode(mode);
        }
    }

    /** Compatibility for callers that only distinguish constrained JSON from prompt-only output. */
    public boolean supportsJsonMode(AiModelConfig config) {
        return preferredStructuredOutputMode(config) != AiStructuredOutputMode.PROMPT_ONLY;
    }

    public void markJsonModeUnsupported(AiModelConfig config) {
        recordStructuredOutputMode(config, AiStructuredOutputMode.PROMPT_ONLY);
    }

    /**
     * Endpoints that failed to stream, so later turns skip straight to the blocking call.
     *
     * <p>Voyager talks to whatever OpenAI-compatible server the operator registered - Ollama,
     * llama.cpp, vLLM, Cloudflare, OpenRouter - and not all of them serve a usable SSE stream. The
     * first failure against an endpoint costs one fallback; the rest are free.
     */
    private final Set<String> nonStreamingEndpoints = ConcurrentHashMap.newKeySet();

    public boolean supportsStreaming(AiModelConfig config) {
        return !nonStreamingEndpoints.contains(endpointKey(config));
    }

    public void markStreamingUnsupported(AiModelConfig config) {
        nonStreamingEndpoints.add(endpointKey(config));
    }

    private String endpointKey(AiModelConfig config) {
        return config.getBaseUrl() + "\0" + config.getModelName();
    }

    /**
     * Same endpoint and credentials as {@link #resolve}, but token-by-token so a turn can report
     * reasoning while the model is still generating instead of after it finishes.
     */
    public StreamingChatModel resolveStreaming(AiModelConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(credential(config))
                .modelName(config.getModelName())
                .timeout(requestTimeout())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private String credential(AiModelConfig config) {
        if (config.getProviderType() != AiModelProviderType.OPENAI_COMPATIBLE_LOCAL
                && config.getProviderType() != AiModelProviderType.OPENAI_COMPATIBLE_API) {
            throw new IllegalArgumentException(
                    "Unsupported AI model provider: " + config.getProviderType()
            );
        }
        String decrypted = secretCipher.decrypt(config.getCredentialEncrypted());
        return decrypted == null || decrypted.isBlank() ? "local" : decrypted;
    }
}
