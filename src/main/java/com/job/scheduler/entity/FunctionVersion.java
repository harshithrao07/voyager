package com.job.scheduler.entity;

import com.job.scheduler.enums.FunctionSourceMode;
import com.job.scheduler.enums.FunctionVersionStatus;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(
        name = "workflow_function_versions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_workflow_function_versions_function_version",
                        columnNames = {"function_id", "version"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_workflow_function_versions_function_created",
                        columnList = "function_id,created_at"
                ),
                @Index(name = "idx_workflow_function_versions_status", columnList = "status")
        }
)
public class FunctionVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "function_id", nullable = false, updatable = false)
    private FunctionDefinition functionDefinition;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_mode", nullable = false)
    private FunctionSourceMode sourceMode = FunctionSourceMode.SINGLE_FILE;

    @Column(name = "language_id", nullable = false)
    private int languageId;

    @Column(name = "source_code", columnDefinition = "TEXT")
    private String sourceCode;

    @Column(name = "additional_files_base64", columnDefinition = "TEXT")
    private String additionalFilesBase64;

    @Column(name = "compiler_options", length = 512)
    private String compilerOptions;

    @Column(name = "command_line_arguments", length = 512)
    private String commandLineArguments;

    @Column(name = "cpu_time_limit_seconds", nullable = false)
    private double cpuTimeLimitSeconds;

    @Column(name = "wall_time_limit_seconds", nullable = false)
    private double wallTimeLimitSeconds;

    @Column(name = "memory_limit_kb", nullable = false)
    private int memoryLimitKb;

    @Column(name = "max_file_size_kb", nullable = false)
    private int maxFileSizeKb;

    @Column(name = "max_output_bytes", nullable = false)
    private int maxOutputBytes;

    @Column(name = "enable_network", nullable = false)
    private boolean enableNetwork;

    // Optional changelog message describing what changed in this version.
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FunctionVersionStatus status = FunctionVersionStatus.AVAILABLE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
