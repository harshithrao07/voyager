package com.job.scheduler.entity;

import com.job.scheduler.enums.WorkflowAiConversationStage;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(
        name = "workflow_ai_conversations",
        indexes = {
                @Index(name = "idx_workflow_ai_conversations_stage", columnList = "stage"),
                @Index(name = "idx_workflow_ai_conversations_updated", columnList = "updated_at")
        }
)
public class WorkflowAiConversation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "initial_instruction", nullable = false, columnDefinition = "TEXT")
    private String initialInstruction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_config_id", nullable = false)
    private AiModelConfig modelConfig;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false)
    private WorkflowAiConversationStage stage =
            WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_asl", columnDefinition = "jsonb")
    private String draftAsl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "final_plan", columnDefinition = "jsonb")
    private String finalPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_workflow_payload", columnDefinition = "jsonb")
    private String draftWorkflowPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
