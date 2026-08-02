package com.job.scheduler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * One embedding vector for a validated workflow solution, used for semantic response caching: a new
 * instruction is embedded and its nearest prior solutions are retrieved so the model can adapt an
 * existing workflow instead of designing cold. Keyed by {@code sourceConversationId} so re-saving
 * the same conversation updates its row rather than accumulating duplicates.
 *
 * <p>Rows are written only from workflows that already passed Voyager's deterministic validators, so
 * the cache never holds an ASL that failed validation. Retrieval only ever seeds the prompt as a
 * template — the regenerated ASL is validated again against the current catalog — so a stale entry
 * is self-healing and never served verbatim.
 *
 * <p>The vector column shares the fixed {@link EmbeddingVector#DIMENSIONS} width used by
 * {@link ResourceEmbedding}; model outputs are zero-padded up to it, so both tiers stay comparable
 * under the default EMBEDDING-role model regardless of that model's native dimension.
 */
@Entity
@Getter
@Setter
@Table(
        name = "workflow_solution_embeddings",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_workflow_solution_embeddings_conversation",
                columnNames = {"source_conversation_id"}
        )
)
public class WorkflowSolutionEmbedding {
    public static final int DIMENSIONS = EmbeddingVector.DIMENSIONS;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** The conversation whose save produced this solution; the upsert key. */
    @Column(name = "source_conversation_id", nullable = false)
    private UUID sourceConversationId;

    /** The workflow created from this solution, for provenance and future pruning. */
    @Column(name = "source_workflow_id")
    private UUID sourceWorkflowId;

    /** The instruction that was embedded — the semantic key and the adaptation prompt text. */
    @Column(name = "instruction_text", nullable = false, columnDefinition = "text")
    private String instructionText;

    /** The validated ASL definition that resulted, seeded into the prompt as an adaptation template. */
    @Column(name = "workflow_asl", nullable = false, columnDefinition = "text")
    private String workflowAsl;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = DIMENSIONS)
    @Column(name = "embedding", nullable = false)
    private float[] embedding;

    @Column(name = "embedding_model", nullable = false)
    private String embeddingModel;

    @Column(name = "dimensions", nullable = false)
    private int dimensions;

    /** Hash of (model + instruction) so an unchanged re-save skips re-embedding. */
    @Column(name = "source_hash", nullable = false, length = 128)
    private String sourceHash;

    @Column(name = "embedded_at", nullable = false)
    private Instant embeddedAt;
}
