package com.job.scheduler.entity;

import com.job.scheduler.enums.JobStatus;
import com.job.scheduler.enums.JobType;
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
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Data
@Table(
        name = "step_executions",
        indexes = {
                @Index(name = "idx_step_executions_execution_log_id", columnList = "execution_log_id"),
                @Index(name = "idx_step_executions_job_step_id", columnList = "job_step_id"),
                @Index(name = "idx_step_executions_status", columnList = "execution_status"),
                @Index(name = "idx_step_executions_created_at", columnList = "created_at")
        }
)
public class StepExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_log_id", nullable = false)
    private ExecutionLog executionLog;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_step_id", nullable = false)
    private JobStep jobStep;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false)
    private JobType stepType;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false)
    private JobStatus executionStatus;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolved_input", columnDefinition = "jsonb")
    private String resolvedInput;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_ref", columnDefinition = "jsonb")
    private String inputRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", columnDefinition = "jsonb")
    private String output;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_ref", columnDefinition = "jsonb")
    private String outputRef;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
