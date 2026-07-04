package com.job.scheduler.entity;

import com.job.scheduler.enums.FunctionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
        name = "workflow_functions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_workflow_functions_namespace_name",
                        columnNames = {"namespace", "name"}
                )
        },
        indexes = {
                @Index(name = "idx_workflow_functions_status", columnList = "status"),
                @Index(name = "idx_workflow_functions_updated_at", columnList = "updated_at")
        }
)
public class FunctionDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "namespace", nullable = false, updatable = false)
    private String namespace;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "active_version")
    private Integer activeVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FunctionStatus status = FunctionStatus.ENABLED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
