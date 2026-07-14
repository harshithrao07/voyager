package com.job.scheduler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(
        name = "workflow_definitions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_workflow_definitions_workflow_revision",
                        columnNames = {"workflow_id", "revision"}
                ),
                @UniqueConstraint(
                        name = "uk_workflow_definitions_workflow_hash",
                        columnNames = {"workflow_id", "definition_hash"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_workflow_definitions_workflow_created_at",
                        columnList = "workflow_id,created_at"
                )
        }
)
public class WorkflowDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false, updatable = false)
    private Workflow workflow;

    @Column(name = "revision", nullable = false, updatable = false)
    private long revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String definition;

    @Column(
            name = "definition_hash",
            nullable = false,
            updatable = false,
            length = 64
    )
    private String definitionHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canvas_layout", columnDefinition = "jsonb")
    private String canvasLayout;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
