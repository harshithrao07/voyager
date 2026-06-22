package com.job.scheduler.entity;

import com.job.scheduler.enums.ExecutionScopeStatus;
import com.job.scheduler.enums.ExecutionScopeType;
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
import jakarta.persistence.UniqueConstraint;
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
        name = "execution_scopes",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_execution_scopes_execution_path",
                        columnNames = {"workflow_execution_id", "scope_path"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_execution_scopes_execution_status",
                        columnList = "workflow_execution_id,status"
                ),
                @Index(
                        name = "idx_execution_scopes_parent_scope_id",
                        columnList = "parent_scope_id"
                ),
                @Index(
                        name = "idx_execution_scopes_status_wake_at",
                        columnList = "status,wake_at"
                )
        }
)
public class ExecutionScope {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workflow_execution_id",
            nullable = false,
            updatable = false
    )
    private WorkflowExecution workflowExecution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_scope_id", updatable = false)
    private ExecutionScope parentScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, updatable = false)
    private ExecutionScopeType scopeType;

    /**
     * Durable identity inside one workflow execution. Examples:
     * root, root/state-3/branch-0, root/state-5/item-12.
     */
    @Column(name = "scope_path", nullable = false, updatable = false, length = 1024)
    private String scopePath;

    @Column(name = "owner_state_name", updatable = false)
    private String ownerStateName;

    @Column(name = "branch_index", updatable = false)
    private Integer branchIndex;

    @Column(name = "item_index", updatable = false)
    private Long itemIndex;

    /**
     * The raw Map array element for this iteration, persisted so that
     * {@code $states.context.Map.Item.Value} can be reconstructed inside the
     * iteration (including after a restart). Null for non-iteration scopes and
     * for batched iterations, where a single per-iteration item value is not
     * defined.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "item_value", columnDefinition = "jsonb", updatable = false)
    private String itemValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionScopeStatus status = ExecutionScopeStatus.PENDING;

    @Column(name = "current_state_name")
    private String currentStateName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_state_input", columnDefinition = "jsonb")
    private String currentStateInput;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", columnDefinition = "jsonb", nullable = false)
    private String variables = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", columnDefinition = "jsonb")
    private String output;

    @Column(name = "wake_at")
    private Instant wakeAt;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "cause", columnDefinition = "TEXT")
    private String cause;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
