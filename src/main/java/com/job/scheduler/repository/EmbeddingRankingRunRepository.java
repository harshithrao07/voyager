package com.job.scheduler.repository;

import com.job.scheduler.entity.EmbeddingRankingRun;
import com.job.scheduler.enums.EmbeddingRankingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmbeddingRankingRunRepository
        extends JpaRepository<EmbeddingRankingRun, UUID> {
    Optional<EmbeddingRankingRun> findFirstByOrderByStartedAtDesc();

    Optional<EmbeddingRankingRun> findFirstByStatusOrderByStartedAtDesc(
            EmbeddingRankingStatus status
    );
}
