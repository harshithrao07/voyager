package com.job.scheduler.entity;

import com.job.scheduler.enums.McpToolExecutionStatus;
import com.job.scheduler.enums.McpTrustLevel;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Data
@Table(
        name = "mcp_tool_executions",
        indexes = {
                @Index(name = "idx_mcp_tool_executions_server_tool", columnList = "server_id,tool_name"),
                @Index(name = "idx_mcp_tool_executions_status", columnList = "status"),
                @Index(name = "idx_mcp_tool_executions_started_at", columnList = "started_at")
        }
)
public class McpToolExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mcp_server_id")
    private McpServer mcpServer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mcp_tool_id")
    private McpTool mcpTool;

    @Column(name = "server_id", nullable = false)
    private String serverId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments", columnDefinition = "jsonb", nullable = false)
    private String arguments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private String result;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private McpToolExecutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "max_allowed_trust_level")
    private McpTrustLevel maxAllowedTrustLevel;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

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
