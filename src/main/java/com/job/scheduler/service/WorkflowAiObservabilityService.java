package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowAiObservabilityDTO;
import com.job.scheduler.dto.WorkflowAiObservabilityDTO.FinishReasonCount;
import com.job.scheduler.dto.WorkflowAiObservabilityDTO.ModelBreakdown;
import com.job.scheduler.dto.WorkflowAiObservabilityDTO.RecentTurn;
import com.job.scheduler.enums.WorkflowAiMessageRole;
import com.job.scheduler.repository.WorkflowAiMessageRepository;
import com.job.scheduler.repository.WorkflowAiMessageRepository.TurnTelemetryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the in-app observability summary from persisted per-turn telemetry. Read-only and bounded
 * (caps rows scanned), so it stays cheap even as message history grows. Distinct from Langfuse: this
 * is the native at-a-glance panel; Langfuse holds the full per-trace timeline.
 */
@Service
@RequiredArgsConstructor
public class WorkflowAiObservabilityService {
    private static final int MAX_ROWS = 10_000;
    private static final int RECENT_LIMIT = 20;
    private static final String UNKNOWN = "unknown";

    private final WorkflowAiMessageRepository messageRepository;

    @Transactional(readOnly = true)
    public WorkflowAiObservabilityDTO metrics(int windowDays) {
        int days = Math.max(1, Math.min(windowDays, 365));
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<TurnTelemetryRow> rows = messageRepository.findTurnTelemetrySince(
                WorkflowAiMessageRole.ASSISTANT, since, PageRequest.of(0, MAX_ROWS)
        );

        long totalInput = 0;
        long totalOutput = 0;
        long totalTokens = 0;
        List<Long> latencies = new ArrayList<>();
        Map<String, ModelAccumulator> byModel = new LinkedHashMap<>();
        Map<String, Integer> finishReasons = new LinkedHashMap<>();

        for (TurnTelemetryRow row : rows) {
            totalInput += nz(row.getInputTokens());
            totalOutput += nz(row.getOutputTokens());
            totalTokens += nz(row.getTotalTokens());

            Long duration = row.getDurationMs();
            if (duration != null) {
                latencies.add(duration);
            }

            String model = blankToUnknown(row.getModelName());
            ModelAccumulator acc = byModel.computeIfAbsent(model, key -> new ModelAccumulator());
            acc.turns++;
            acc.totalTokens += nz(row.getTotalTokens());
            if (duration != null) {
                acc.latencySum += duration;
                acc.latencyCount++;
            }

            String reason = blankToUnknown(row.getFinishReason());
            finishReasons.merge(reason, 1, Integer::sum);
        }

        List<ModelBreakdown> modelBreakdowns = byModel.entrySet().stream()
                .map(entry -> new ModelBreakdown(
                        entry.getKey(),
                        entry.getValue().turns,
                        entry.getValue().totalTokens,
                        entry.getValue().avgLatency()
                ))
                .sorted(Comparator.comparingInt(ModelBreakdown::turns).reversed())
                .toList();

        List<FinishReasonCount> finishReasonCounts = finishReasons.entrySet().stream()
                .map(entry -> new FinishReasonCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(FinishReasonCount::count).reversed())
                .toList();

        List<RecentTurn> recent = rows.stream()
                .limit(RECENT_LIMIT)
                .map(row -> new RecentTurn(
                        row.getCreatedAt(),
                        blankToUnknown(row.getModelName()),
                        row.getDurationMs(),
                        row.getTotalTokens(),
                        blankToUnknown(row.getFinishReason())
                ))
                .toList();

        return new WorkflowAiObservabilityDTO(
                days,
                rows.size(),
                totalInput,
                totalOutput,
                totalTokens,
                average(latencies),
                percentile(latencies, 50),
                percentile(latencies, 95),
                modelBreakdowns,
                finishReasonCounts,
                recent
        );
    }

    private static double average(List<Long> values) {
        if (values.isEmpty()) {
            return 0d;
        }
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return round1((double) sum / values.size());
    }

    /** Nearest-rank percentile over latency samples (0 when there are none). */
    private static double percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return 0d;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        int index = Math.min(Math.max(rank - 1, 0), sorted.size() - 1);
        return round1(sorted.get(index));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static long nz(Integer value) {
        return value == null ? 0L : value;
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    private static final class ModelAccumulator {
        int turns;
        long totalTokens;
        long latencySum;
        int latencyCount;

        double avgLatency() {
            return latencyCount == 0 ? 0d : round1((double) latencySum / latencyCount);
        }
    }
}
