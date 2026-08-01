package com.job.scheduler.repository;

import com.job.scheduler.entity.ResourceEmbedding;
import com.job.scheduler.enums.ResourceEmbeddingType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResourceEmbeddingRepository
        extends JpaRepository<ResourceEmbedding, UUID> {

    Optional<ResourceEmbedding> findByResourceTypeAndResourceId(
            ResourceEmbeddingType resourceType,
            UUID resourceId
    );

    void deleteByResourceTypeAndResourceId(
            ResourceEmbeddingType resourceType,
            UUID resourceId
    );

    /** Resource ids that currently have an embedding of this type (for reconcile diffing). */
    @Query("""
            SELECT e.resourceId
            FROM ResourceEmbedding e
            WHERE e.resourceType = :resourceType
            """)
    List<UUID> findResourceIdsByType(@Param("resourceType") ResourceEmbeddingType resourceType);

    /** Current source hash for a resource, without loading its vector (staleness check). */
    @Query("""
            SELECT e.sourceHash
            FROM ResourceEmbedding e
            WHERE e.resourceType = :resourceType AND e.resourceId = :resourceId
            """)
    Optional<String> findSourceHash(
            @Param("resourceType") ResourceEmbeddingType resourceType,
            @Param("resourceId") UUID resourceId
    );

    /**
     * Top nearest resource ids for a query vector, ranked by cosine distance (ascending —
     * nearest first). {@code cosine_distance} is the hibernate-vector HQL function backed by
     * pgvector's {@code <=>} operator and its HNSW cosine index. Pass {@code Pageable} of
     * {@code PageRequest.of(0, k)} to cap at k results.
     */
    @Query("""
            SELECT e.resourceId
            FROM ResourceEmbedding e
            WHERE e.resourceType = :resourceType
            ORDER BY cosine_distance(e.embedding, :query)
            """)
    List<UUID> findNearestResourceIds(
            @Param("resourceType") ResourceEmbeddingType resourceType,
            @Param("query") float[] query,
            Pageable pageable
    );
}
