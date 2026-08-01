package com.job.scheduler.dto;

import java.time.Instant;
import java.util.List;

/**
 * A completed retrieval-ranking result: the models ranked best-first by recall@k then MRR, plus the
 * eval parameters (top-k, catalog size, how many queries were scored) for context.
 */
public record EmbeddingRankingResultDTO(
        int k,
        int catalogSize,
        int totalQueries,
        Instant generatedAt,
        List<EmbeddingRankingModelResultDTO> models
) {
}
