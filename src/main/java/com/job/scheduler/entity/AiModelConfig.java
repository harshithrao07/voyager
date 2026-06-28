package com.job.scheduler.entity;

import com.job.scheduler.enums.AiModelProviderType;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(
        name = "ai_model_configs",
        indexes = {
                @Index(name = "idx_ai_model_configs_enabled", columnList = "enabled"),
                @Index(name = "idx_ai_model_configs_default", columnList = "default_model")
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

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "default_model", nullable = false)
    private boolean defaultModel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
