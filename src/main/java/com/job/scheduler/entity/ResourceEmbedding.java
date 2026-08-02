package com.job.scheduler.entity;

import com.job.scheduler.enums.ResourceEmbeddingType;
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
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One embedding vector for a catalog resource (a function or an MCP tool), used for
 * retrieval-augmented catalog matching. Keyed by {@code (resourceType, resourceId)}; the
 * {@code sourceHash} lets the embedder skip resources whose embedded text and model are
 * unchanged. The vector column is a fixed {@link EmbeddingVector#DIMENSIONS}-wide pgvector column;
 * model outputs are zero-padded up to it, so any model of that dimension or fewer is comparable.
 */
@Entity
@Getter
@Setter
@Table(
        name = "resource_embeddings",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_resource_embeddings_resource",
                columnNames = {"resource_type", "resource_id"}
        ),
        indexes = @Index(
                name = "idx_resource_embeddings_type",
                columnList = "resource_type"
        )
)
public class ResourceEmbedding {
    public static final int DIMENSIONS = EmbeddingVector.DIMENSIONS;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    private ResourceEmbeddingType resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = DIMENSIONS)
    @Column(name = "embedding", nullable = false)
    private float[] embedding;

    @Column(name = "embedding_model", nullable = false)
    private String embeddingModel;

    @Column(name = "dimensions", nullable = false)
    private int dimensions;

    @Column(name = "source_hash", nullable = false, length = 128)
    private String sourceHash;

    @Column(name = "embedded_at", nullable = false)
    private Instant embeddedAt;
}
