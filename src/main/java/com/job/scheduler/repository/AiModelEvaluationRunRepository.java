package com.job.scheduler.repository;

import com.job.scheduler.entity.AiModelEvaluationRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AiModelEvaluationRunRepository
        extends JpaRepository<AiModelEvaluationRun, UUID> {
    Page<AiModelEvaluationRun> findByModelConfigIdOrderByStartedAtDesc(
            UUID modelConfigId,
            Pageable pageable
    );
}
