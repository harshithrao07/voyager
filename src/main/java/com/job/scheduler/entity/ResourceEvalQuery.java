package com.job.scheduler.entity;

import com.job.scheduler.enums.ResourceEmbeddingType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A cached synthetic query for one catalog resource — the ground truth for retrieval ranking. The
 * default chat model writes a natural-language request a user might type to reach the resource; the
 * embedding ranking then checks whether each embedding model ranks that resource near the top for
 * its query. Regenerated only when the resource's text changes ({@code sourceHash} mismatch).
 */
@Entity
@Getter
@Setter
@Table(
        name = "resource_eval_queries",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_resource_eval_queries_resource",
                columnNames = {"resource_type", "resource_id"}
        )
)
public class ResourceEvalQuery {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    private ResourceEmbeddingType resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "query_text", nullable = false, columnDefinition = "TEXT")
    private String queryText;

    @Column(name = "source_hash", nullable = false, length = 128)
    private String sourceHash;

    @Column(name = "generated_by_model")
    private String generatedByModel;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
