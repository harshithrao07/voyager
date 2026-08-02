package com.job.scheduler.entity;

/**
 * Single source of truth for the pgvector column width shared by every embedding table
 * ({@link ResourceEmbedding}, {@link WorkflowSolutionEmbedding}). The column is fixed at this width
 * and model vectors are zero-padded up to it by {@code WorkflowAiEmbeddingService.embed}. Trailing
 * zeros leave cosine distance unchanged (they add nothing to the dot product or either magnitude),
 * so a shorter-dimension model stays fully comparable — which lets the embedding model change to any
 * model of this dimension or fewer without recompiling or migrating the schema.
 *
 * <p>{@value #DIMENSIONS} is chosen as pgvector's {@code halfvec} index ceiling: it covers every
 * common embedding model (768, 1024, 1536, 3072) while keeping a future halfvec HNSW index possible.
 * Raising it past 4000 would forfeit that option. Changing it still requires recreating the vector
 * tables (Hibernate {@code ddl-auto=update} will not widen a {@code vector(N)} column) and a rebuild.
 */
public final class EmbeddingVector {
    private EmbeddingVector() {
    }

    public static final int DIMENSIONS = 4000;
}
