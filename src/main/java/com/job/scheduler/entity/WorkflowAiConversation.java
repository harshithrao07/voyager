package com.job.scheduler.entity;

import com.job.scheduler.enums.WorkflowAiConversationStage;
import com.job.scheduler.enums.WorkflowAiWorkspaceKind;
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

    /** User-selected sidebar label. Null keeps the automatically generated workspace name. */
    @Column(name = "custom_name", length = 120)
    private String customName;

    @Column(name = "initial_instruction", nullable = false, columnDefinition = "TEXT")
    private String initialInstruction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_config_id")
    private AiModelConfig modelConfig;

    @Enumerated(EnumType.STRING)
    @Column(name = "workspace_kind", nullable = false)
    private WorkflowAiWorkspaceKind workspaceKind = WorkflowAiWorkspaceKind.AI_CHAT;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false)
    private WorkflowAiConversationStage stage =
            WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_asl", columnDefinition = "jsonb")
    private String draftAsl;

    /** Raw editor buffer, including temporarily invalid/incomplete JSON. */
    @Column(name = "workspace_definition_text", columnDefinition = "TEXT")
    private String workspaceDefinitionText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "final_plan", columnDefinition = "jsonb")
    private String finalPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_workflow_payload", columnDefinition = "jsonb")
    private String draftWorkflowPayload;

    /** Functions/MCP capabilities the model proposed but that don't yet exist in the catalog. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource_plan", columnDefinition = "jsonb")
    private String resourcePlan;

    /** Assistant message that owns the currently pending resource-plan card. */
    @Column(name = "resource_plan_message_id")
    private UUID resourcePlanMessageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canvas_layout", columnDefinition = "jsonb")
    private String canvasLayout;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "workspace_settings", columnDefinition = "jsonb")
    private String workspaceSettings;

    /** Workflow created from this workspace. Later saves become revisions of this workflow. */
    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "conversation_summary", columnDefinition = "TEXT")
    private String conversationSummary;

    @Column(name = "summarized_through_message_id")
    private UUID summarizedThroughMessageId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
