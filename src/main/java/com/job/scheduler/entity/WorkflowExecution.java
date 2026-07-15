package com.job.scheduler.entity;

import com.job.scheduler.enums.WorkflowExecutionStatus;
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
        name = "workflow_executions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_workflow_executions_workflow_run_number",
                        columnNames = {"workflow_id", "run_number"}
                ),
                @UniqueConstraint(
                        name = "uk_workflow_executions_workflow_scheduled_for",
                        columnNames = {"workflow_id", "scheduled_for"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_workflow_executions_workflow_created_at",
                        columnList = "workflow_id,created_at"
                ),
                @Index(
                        name = "idx_workflow_executions_status_scheduled_for",
                        columnList = "status,scheduled_for"
                ),
                @Index(
                        name = "idx_workflow_executions_status_completed_at",
                        columnList = "status,completed_at"
                ),
                @Index(
                        name = "idx_workflow_executions_definition_id",
                        columnList = "workflow_definition_id"
                )
        }
)
public class WorkflowExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false, updatable = false)
    private Workflow workflow;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workflow_definition_id",
            nullable = false,
            updatable = false
    )
    private WorkflowDefinition workflowDefinition;

    @Column(name = "run_number", nullable = false, updatable = false)
    private long runNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WorkflowExecutionStatus status = WorkflowExecutionStatus.PENDING;

    @Column(name = "scheduled_for", updatable = false)
    private Instant scheduledFor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String input = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", columnDefinition = "jsonb")
    private String output;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "cause", columnDefinition = "TEXT")
    private String cause;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

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
