package com.job.scheduler.entity;

import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.AiModelEvaluationMode;
import com.job.scheduler.enums.AiModelEvaluationStatus;
import com.job.scheduler.enums.AiModelRole;
import com.job.scheduler.enums.AiStructuredOutputMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(
        name = "ai_model_configs",
        indexes = {
                @Index(name = "idx_ai_model_configs_enabled", columnList = "enabled"),
                @Index(name = "idx_ai_model_configs_default", columnList = "default_model"),
                @Index(name = "idx_ai_model_configs_role", columnList = "role")
        }
)
public class AiModelConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false)
    private AiModelProviderType providerType = AiModelProviderType.OPENAI_COMPATIBLE_LOCAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private AiModelRole role = AiModelRole.CHAT;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** AES-256-GCM encrypted credential (e.g. API key), stored inline. Null when none. */
    @Column(name = "credential_encrypted", columnDefinition = "TEXT")
    private String credentialEncrypted;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "default_model", nullable = false)
    private boolean defaultModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "structured_output_mode", nullable = false)
    private AiStructuredOutputMode structuredOutputMode = AiStructuredOutputMode.UNKNOWN;

    @Column(name = "evaluation_run_id")
    private UUID evaluationRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_status")
    private AiModelEvaluationStatus evaluationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_mode")
    private AiModelEvaluationMode evaluationMode;

    @Column(name = "evaluation_repetitions")
    private Integer evaluationRepetitions;

    @Column(name = "evaluation_completed_cases")
    private Integer evaluationCompletedCases;

    @Column(name = "evaluation_total_cases")
    private Integer evaluationTotalCases;

    @Column(name = "evaluation_cancel_requested", nullable = false)
    private boolean evaluationCancelRequested;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_result", columnDefinition = "JSONB")
    private String evaluationResult;

    @Column(name = "evaluation_error", columnDefinition = "TEXT")
    private String evaluationError;

    @Column(name = "evaluation_started_at")
    private Instant evaluationStartedAt;

    @Column(name = "evaluation_finished_at")
    private Instant evaluationFinishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
