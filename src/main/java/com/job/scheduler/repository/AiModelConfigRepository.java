package com.job.scheduler.repository;

import com.job.scheduler.entity.AiModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiModelConfigRepository
        extends JpaRepository<AiModelConfig, UUID> {
    List<AiModelConfig> findByEnabledTrueOrderByDefaultModelDescDisplayNameAsc();

    Optional<AiModelConfig> findFirstByEnabledTrueOrderByDefaultModelDescDisplayNameAsc();

    long countByEnabledTrue();
}
