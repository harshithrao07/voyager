package com.job.scheduler.entity;

import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Data
@Table(
        name = "mcp_servers",
        indexes = {
                @Index(name = "idx_mcp_servers_status", columnList = "status"),
                @Index(name = "idx_mcp_servers_trust_level", columnList = "trust_level")
        }
)
public class McpServer {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "server_id", nullable = false, unique = true, updatable = false)
    private String serverId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "endpoint", nullable = false)
    private String endpoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport", nullable = false)
    private McpTransport transport;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    private McpAuthType authType;

    @Column(name = "auth_token_ref")
    private String authTokenRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false)
    private McpTrustLevel trustLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private McpServerStatus status;

    /** Per-request timeout in ms for this server; null falls back to the app default. */
    @Column(name = "request_timeout_ms")
    private Integer requestTimeoutMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
