package com.job.scheduler.entity;

import com.job.scheduler.enums.FunctionInvocationStatus;
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
        name = "workflow_function_invocations",
        indexes = {
                @Index(
                        name = "idx_workflow_function_invocations_function_started",
                        columnList = "function_id,started_at"
                ),
                @Index(
                        name = "idx_workflow_function_invocations_status_started",
                        columnList = "status,started_at"
                ),
                @Index(
                        name = "idx_workflow_function_invocations_workflow_execution",
                        columnList = "workflow_execution_id"
                ),
                @Index(
                        name = "idx_workflow_function_invocations_state_attempt",
                        columnList = "state_execution_attempt_id"
                ),
                @Index(
                        name = "idx_workflow_function_invocations_judge0_token",
                        columnList = "judge0_token"
                )
        }
)
public class FunctionInvocation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "function_id", nullable = false, updatable = false)
    private FunctionDefinition functionDefinition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "function_version_id", nullable = false, updatable = false)
    private FunctionVersion functionVersion;

    @Column(name = "workflow_execution_id")
    private UUID workflowExecutionId;

    @Column(name = "state_name")
    private String stateName;

    @Column(name = "state_execution_attempt_id")
    private UUID stateExecutionAttemptId;

    @Column(name = "judge0_token")
    private String judge0Token;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FunctionInvocationStatus status = FunctionInvocationStatus.RUNNING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", columnDefinition = "jsonb", nullable = false)
    private String inputJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", columnDefinition = "jsonb")
    private String outputJson;

    @Column(name = "stdout", columnDefinition = "TEXT")
    private String stdout;

    @Column(name = "stderr", columnDefinition = "TEXT")
    private String stderr;

    @Column(name = "compile_output", columnDefinition = "TEXT")
    private String compileOutput;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "exit_signal")
    private Integer exitSignal;

    @Column(name = "judge0_status_id")
    private Integer judge0StatusId;

    @Column(name = "judge0_status_description")
    private String judge0StatusDescription;

    @Column(name = "error_name")
    private String errorName;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "time_seconds")
    private Double timeSeconds;

    @Column(name = "wall_time_seconds")
    private Double wallTimeSeconds;

    @Column(name = "memory_kb")
    private Long memoryKb;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
