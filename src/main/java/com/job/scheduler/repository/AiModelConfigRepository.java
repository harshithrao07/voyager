package com.job.scheduler.repository;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiModelConfigRepository
        extends JpaRepository<AiModelConfig, UUID> {
    List<AiModelConfig> findByEnabledTrueOrderByDefaultModelDescDisplayNameAsc();

    List<AiModelConfig> findByEnabledTrueAndRoleOrderByDefaultModelDescDisplayNameAsc(
            AiModelRole role
    );

    List<AiModelConfig> findAllByOrderByBaseUrlAscDisplayNameAsc();

    Optional<AiModelConfig> findFirstByEnabledTrueOrderByDefaultModelDescDisplayNameAsc();

    Optional<AiModelConfig> findFirstByEnabledTrueAndRoleOrderByDefaultModelDescDisplayNameAsc(
            AiModelRole role
    );

    long countByEnabledTrue();

    long countByEnabledTrueAndRole(AiModelRole role);

    Optional<AiModelConfig> findByBaseUrlAndModelName(String baseUrl, String modelName);

    Optional<AiModelConfig> findFirstByBaseUrlOrderByCreatedAtAsc(String baseUrl);
}
