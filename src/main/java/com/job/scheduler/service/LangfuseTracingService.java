package com.job.scheduler.service;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Ships one OpenTelemetry trace per workflow-AI turn to self-hosted Langfuse for LLM observability.
 * The trace is reconstructed from telemetry the app already captures (tokens, duration, model,
 * stage), so nothing instruments the live model call. Every path is failure-safe: a missing config,
 * an unreachable Langfuse, or an export error never affects the turn.
 *
 * <p>Langfuse ingests OTLP over HTTP at {@code /api/public/otel/v1/traces} with the project keys as
 * HTTP basic auth. It maps a root span to the trace and any span carrying a model attribute to a
 * "generation" observation; attribute names follow Langfuse's OTel mapping ({@code langfuse.*} and
 * {@code gen_ai.*}).
 */
@Slf4j
@Service
public class LangfuseTracingService {

    /** Cap per-attribute text so a large prompt/ASL never bloats a span. */
    private static final int MAX_ATTR_CHARS = 10_000;

    @Value("${scheduler.langfuse.tracing.enabled:false}")
    private boolean enabled;

    @Value("${scheduler.langfuse.host:}")
    private String host;

    @Value("${scheduler.langfuse.public-key:}")
    private String publicKey;

    @Value("${scheduler.langfuse.secret-key:}")
    private String secretKey;

    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    /** One trace's worth of turn telemetry, all optional except what the turn actually produced. */
    public record TurnTrace(
            String conversationId,
            String traceName,
            String userInput,
            String assistantOutput,
            String modelName,
            String stage,
            String promptFingerprint,
            long durationMs,
            Integer inputTokens,
            Integer outputTokens,
            Instant endedAt
    ) {
    }

    @PostConstruct
    void init() {
        if (!enabled || isBlank(host) || isBlank(publicKey) || isBlank(secretKey)) {
            log.info("Langfuse tracing disabled (enabled={}, host set={})", enabled, !isBlank(host));
            return;
        }
        try {
            String auth = Base64.getEncoder().encodeToString(
                    (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8)
            );
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(stripTrailingSlash(host) + "/api/public/otel/v1/traces")
                    .addHeader("Authorization", "Basic " + auth)
                    .build();
            tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .setResource(Resource.getDefault().merge(Resource.create(
                            Attributes.of(AttributeKey.stringKey("service.name"), "voyager-workflow-ai")
                    )))
                    .build();
            tracer = tracerProvider.get("voyager.workflow-ai");
            log.info("Langfuse tracing enabled -> {}", stripTrailingSlash(host) + "/api/public/otel");
        } catch (RuntimeException exception) {
            log.warn("Langfuse tracing init failed; tracing off: {}", exception.getMessage());
            tracer = null;
        }
    }

    @PreDestroy
    void shutdown() {
        if (tracerProvider != null) {
            tracerProvider.shutdown();
        }
    }

    /**
     * Emits one trace (a root span plus a generation child span) for a completed turn. No-op when
     * tracing is off; never throws.
     */
    public void recordTurn(TurnTrace turn) {
        if (tracer == null || turn == null) {
            return;
        }
        try {
            Instant end = turn.endedAt() != null ? turn.endedAt() : Instant.now();
            Instant start = end.minusMillis(Math.max(0L, turn.durationMs()));
            String name = isBlank(turn.traceName()) ? "workflow-ai-turn" : turn.traceName();

            Span root = tracer.spanBuilder(name).setStartTimestamp(start).startSpan();
            try {
                root.setAttribute("langfuse.trace.name", name);
                setIfPresent(root, "langfuse.session.id", turn.conversationId());
                setIfPresent(root, "langfuse.trace.input", truncate(turn.userInput()));
                setIfPresent(root, "langfuse.trace.output", truncate(turn.assistantOutput()));
                setIfPresent(root, "langfuse.trace.metadata.stage", turn.stage());
                setIfPresent(root, "langfuse.trace.metadata.prompt_fingerprint", turn.promptFingerprint());
                setIfPresent(root, "langfuse.trace.metadata.model", turn.modelName());

                Span generation = tracer.spanBuilder("generation")
                        .setParent(Context.current().with(root))
                        .setStartTimestamp(start)
                        .startSpan();
                try {
                    generation.setAttribute("langfuse.observation.type", "generation");
                    // A model attribute is what makes Langfuse treat this span as a generation.
                    setIfPresent(generation, "gen_ai.request.model", turn.modelName());
                    setIfPresent(generation, "langfuse.observation.input", truncate(turn.userInput()));
                    setIfPresent(generation, "langfuse.observation.output", truncate(turn.assistantOutput()));
                    if (turn.inputTokens() != null) {
                        generation.setAttribute("gen_ai.usage.input_tokens", turn.inputTokens().longValue());
                    }
                    if (turn.outputTokens() != null) {
                        generation.setAttribute("gen_ai.usage.output_tokens", turn.outputTokens().longValue());
                    }
                } finally {
                    generation.end(end);
                }
            } finally {
                root.end(end);
            }
        } catch (RuntimeException exception) {
            log.warn("Langfuse trace emission failed: {}", exception.getMessage());
        }
    }

    private static void setIfPresent(Span span, String key, String value) {
        if (!isBlank(value)) {
            span.setAttribute(key, value);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ATTR_CHARS ? value : value.substring(0, MAX_ATTR_CHARS) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
