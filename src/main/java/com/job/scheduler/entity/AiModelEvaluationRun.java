package com.job.scheduler.entity;

import com.job.scheduler.enums.AiModelEvaluationMode;
import com.job.scheduler.enums.AiModelEvaluationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable snapshot of one model benchmark run.
 *
 * <p>{@link AiModelConfig} keeps the latest run inline for fast ranking reads. This ledger keeps
 * every run so operators can inspect earlier results without changing how the latest ranking is
 * calculated.
 */
@Entity
@Getter
@Setter
@Table(
        name = "ai_model_evaluation_runs",
        indexes = {
                @Index(
                        name = "idx_ai_model_evaluation_runs_model_started",
                        columnList = "model_config_id,started_at"
                )
        }
)
public class AiModelEvaluationRun {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "model_config_id", nullable = false, updatable = false)
    private UUID modelConfigId;

    @Column(name = "model_display_name", nullable = false)
    private String modelDisplayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiModelEvaluationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiModelEvaluationMode mode;

    @Column(nullable = false)
    private int repetitions;

    @Column(name = "completed_cases", nullable = false)
    private int completedCases;

    @Column(name = "total_cases", nullable = false)
    private int totalCases;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String result;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
