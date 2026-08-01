package com.job.scheduler.dto;

import java.util.UUID;

/**
 * Retrieval metrics for one embedding model. recall@1 is how often the correct resource ranked
 * first, recall@k how often it landed in the production top-k, and MRR the mean reciprocal rank
 * (higher = ranked nearer the top). {@code error} is set instead of metrics when the model could
 * not be evaluated (e.g. unreachable, or a dimension that doesn't match the query embeddings).
 */
public record EmbeddingRankingModelResultDTO(
        UUID modelId,
        String displayName,
        String modelName,
        Integer dimensions,
        Double recallAt1,
        Double recallAtK,
        Double mrr,
        Double avgLatencyMs,
        Integer queriesEvaluated,
        String error
) {
}
