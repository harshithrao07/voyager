package com.job.scheduler.entity;

import com.job.scheduler.enums.StateExecutionAttemptKind;
import com.job.scheduler.enums.StateExecutionAttemptStatus;
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
        name = "state_execution_attempts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_state_execution_attempts_state_attempt",
                        columnNames = {"state_execution_id", "attempt_number"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_state_execution_attempts_state_status",
                        columnList = "state_execution_id,status"
                ),
                @Index(
                        name = "idx_state_execution_attempts_status_available_at",
                        columnList = "status,available_at"
                ),
                @Index(
                        name = "idx_state_execution_attempts_status_queued_at",
                        columnList = "status,queued_at"
                ),
                @Index(
                        name = "idx_state_execution_attempts_status_started_at",
                        columnList = "status,started_at"
                ),
                @Index(
                        name = "idx_state_execution_attempts_status_timeout_at",
                        columnList = "status,timeout_at"
                ),
                @Index(
                        name = "idx_state_execution_attempts_status_heartbeat_deadline",
                        columnList = "status,heartbeat_deadline_at"
                )
        }
)
public class StateExecutionAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_execution_id", nullable = false, updatable = false)
    private StateExecution stateExecution;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private StateExecutionAttemptKind kind = StateExecutionAttemptKind.TASK;

    /**
     * Resource to execute for this attempt. Null for normal Task attempts (the
     * worker falls back to the owning StateExecution's resource); set for Map
     * READER/WRITER attempts, whose fork StateExecution has no resource.
     */
    @Column(name = "resource", updatable = false, length = 2048)
    private String resource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StateExecutionAttemptStatus status =
            StateExecutionAttemptStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments", columnDefinition = "jsonb", updatable = false)
    private String arguments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private String result;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "available_at")
    private Instant availableAt;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "timeout_seconds", updatable = false)
    private Long timeoutSeconds;

    @Column(name = "heartbeat_seconds", updatable = false)
    private Long heartbeatSeconds;

    @Column(name = "timeout_at")
    private Instant timeoutAt;

    @Column(name = "heartbeat_deadline_at")
    private Instant heartbeatDeadlineAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "cause", columnDefinition = "TEXT")
    private String cause;

    @Column(name = "dispatch_attempt_count", nullable = false)
    private int dispatchAttemptCount;

    @Column(name = "last_dispatch_error", columnDefinition = "TEXT")
    private String lastDispatchError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
