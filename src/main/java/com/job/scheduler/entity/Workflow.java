package com.job.scheduler.entity;

import com.job.scheduler.enums.WorkflowStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(
        name = "workflows",
        indexes = {
                @Index(name = "idx_workflows_status_next_run_at", columnList = "status,next_run_at"),
                @Index(name = "idx_workflows_created_at", columnList = "created_at"),
                @Index(name = "idx_workflows_active_definition_id", columnList = "active_definition_id")
        }
)
public class Workflow {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WorkflowStatus status = WorkflowStatus.DRAFT;

    @Column(name = "cron_expression")
    private String cronExpression;

    @Column(name = "timezone", nullable = false)
    @ColumnDefault("'UTC'")
    private String timezone = "UTC";

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "max_attempts", nullable = false)
    @ColumnDefault("3")
    private int maxAttempts = 3;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_definition_id")
    private WorkflowDefinition activeDefinition;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
