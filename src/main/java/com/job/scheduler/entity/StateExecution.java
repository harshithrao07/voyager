package com.job.scheduler.entity;

import com.job.scheduler.enums.AslStateType;
import com.job.scheduler.enums.StateExecutionStatus;
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
        name = "state_executions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_state_executions_scope_sequence",
                        columnNames = {"execution_scope_id", "sequence_number"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_state_executions_scope_status",
                        columnList = "execution_scope_id,status"
                ),
                @Index(
                        name = "idx_state_executions_scope_state_name",
                        columnList = "execution_scope_id,state_name"
                ),
                @Index(
                        name = "idx_state_executions_status_retry_at",
                        columnList = "status,retry_at"
                )
        }
)
public class StateExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_scope_id", nullable = false, updatable = false)
    private ExecutionScope executionScope;

    /**
     * Monotonic visit number within the scope. A loop that returns to the same
     * ASL state creates another StateExecution with a new sequence number.
     */
    @Column(name = "sequence_number", nullable = false, updatable = false)
    private long sequenceNumber;

    @Column(name = "state_name", nullable = false, updatable = false)
    private String stateName;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_type", nullable = false, updatable = false)
    private AslStateType stateType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StateExecutionStatus status = StateExecutionStatus.PENDING;

    @Column(name = "resource", updatable = false, length = 2048)
    private String resource;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", columnDefinition = "jsonb")
    private String output;

    @Column(name = "retry_at")
    private Instant retryAt;

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
