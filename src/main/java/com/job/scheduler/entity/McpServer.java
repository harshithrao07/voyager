package com.job.scheduler.entity;

import com.job.scheduler.entity.converter.StringListJsonConverter;
import com.job.scheduler.entity.converter.StringMapJsonConverter;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.List;
import java.util.Map;
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

    /** HTTP transport: base URL. Null for STDIO. */
    @Column(name = "base_url")
    private String baseUrl;

    /** HTTP transport: request path. Null for STDIO. */
    @Column(name = "endpoint")
    private String endpoint;

    /** STDIO transport: executable to launch. Null for HTTP. */
    @Column(name = "command")
    private String command;

    /** STDIO transport: command-line arguments. */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "args", columnDefinition = "TEXT")
    private List<String> args;

    /** STDIO transport: non-secret environment variables for the child process. */
    @Convert(converter = StringMapJsonConverter.class)
    @Column(name = "env", columnDefinition = "TEXT")
    private Map<String, String> env;

    /** STDIO + BEARER_TOKEN: env var the resolved token is injected into. */
    @Column(name = "auth_env_var")
    private String authEnvVar;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport", nullable = false)
    private McpTransport transport;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    private McpAuthType authType;

    @Column(name = "auth_token_ref")
    private String authTokenRef;

    /** API_KEY auth: the header name the resolved secret is sent in (HTTP). */
    @Column(name = "auth_header_name")
    private String authHeaderName;

    /** BASIC auth: the username paired with the resolved secret (HTTP). */
    @Column(name = "auth_username")
    private String authUsername;

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
