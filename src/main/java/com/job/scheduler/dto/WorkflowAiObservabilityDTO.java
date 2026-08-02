package com.job.scheduler.dto;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated workflow-AI turn metrics for the in-app observability panel, computed from the
 * telemetry the app already persists per assistant message. Complements (does not replace) the
 * Langfuse trace UI: this is the quick native summary, Langfuse is the deep per-trace view.
 */
public record WorkflowAiObservabilityDTO(
        int windowDays,
        int totalTurns,
        long totalInputTokens,
        long totalOutputTokens,
        long totalTokens,
        double avgLatencyMs,
        double p50LatencyMs,
        double p95LatencyMs,
        List<ModelBreakdown> byModel,
        List<FinishReasonCount> finishReasons,
        List<RecentTurn> recent
) {
    /** Per-model rollup, one row per distinct model used in the window. */
    public record ModelBreakdown(
            String model,
            int turns,
            long totalTokens,
            double avgLatencyMs
    ) {
    }

    /** Turn count by model finish reason — a lightweight failure taxonomy. */
    public record FinishReasonCount(String reason, int count) {
    }

    /** A recent turn for the at-a-glance list. */
    public record RecentTurn(
            Instant createdAt,
            String model,
            Long durationMs,
            Integer totalTokens,
            String finishReason
    ) {
    }
}
