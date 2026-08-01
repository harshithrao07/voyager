package com.job.scheduler.repository;

import com.job.scheduler.entity.ResourceEvalQuery;
import com.job.scheduler.enums.ResourceEmbeddingType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ResourceEvalQueryRepository
        extends JpaRepository<ResourceEvalQuery, UUID> {
    Optional<ResourceEvalQuery> findByResourceTypeAndResourceId(
            ResourceEmbeddingType resourceType,
            UUID resourceId
    );
}
