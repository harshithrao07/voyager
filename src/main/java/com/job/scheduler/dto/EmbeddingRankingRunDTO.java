package com.job.scheduler.dto;

import com.job.scheduler.enums.EmbeddingRankingStatus;

import java.time.Instant;
import java.util.UUID;

/** A ranking run's status and (once COMPLETED) its result; {@code result} is null while RUNNING/FAILED. */
public record EmbeddingRankingRunDTO(
        UUID id,
        EmbeddingRankingStatus status,
        EmbeddingRankingResultDTO result,
        String error,
        Instant startedAt,
        Instant finishedAt
) {
}
