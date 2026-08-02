package com.job.scheduler.repository;

import com.job.scheduler.entity.WorkflowSolutionEmbedding;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowSolutionEmbeddingRepository
        extends JpaRepository<WorkflowSolutionEmbedding, UUID> {

    Optional<WorkflowSolutionEmbedding> findBySourceConversationId(UUID sourceConversationId);

    /** Rows embedded with a model other than the given one — stale after an EMBEDDING-model switch. */
    List<WorkflowSolutionEmbedding> findByEmbeddingModelNot(String embeddingModel);

    /**
     * Nearest cached solutions for a query vector, each paired with its cosine distance (ascending —
     * nearest first). Returns {@code Object[]{WorkflowSolutionEmbedding, Double}} rows so the caller
     * can apply its own distance band. {@code cosine_distance} is the hibernate-vector HQL function
     * backed by pgvector's {@code <=>} operator. Pass {@code PageRequest.of(0, k)} to cap at k.
     */
    @Query("""
            SELECT e, cosine_distance(e.embedding, :query)
            FROM WorkflowSolutionEmbedding e
            ORDER BY cosine_distance(e.embedding, :query)
            """)
    List<Object[]> findNearestWithDistance(
            @Param("query") float[] query,
            Pageable pageable
    );
}
