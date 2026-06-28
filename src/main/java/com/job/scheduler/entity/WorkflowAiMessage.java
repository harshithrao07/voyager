package com.job.scheduler.entity;

import com.job.scheduler.enums.WorkflowAiMessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(
        name = "workflow_ai_messages",
        indexes = {
                @Index(
                        name = "idx_workflow_ai_messages_conversation_created",
                        columnList = "conversation_id,created_at"
                )
        }
)
public class WorkflowAiMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private WorkflowAiConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_config_id")
    private AiModelConfig modelConfig;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "regenerated_from_message_id")
    private WorkflowAiMessage regeneratedFromMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private WorkflowAiMessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_payload", columnDefinition = "jsonb")
    private String structuredPayload;

    @Column(name = "thinking_content", columnDefinition = "TEXT")
    private String thinkingContent;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "finish_reason", length = 128)
    private String finishReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private String metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
