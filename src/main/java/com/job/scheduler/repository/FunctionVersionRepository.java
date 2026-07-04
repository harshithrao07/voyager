package com.job.scheduler.repository;

import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.FunctionVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FunctionVersionRepository extends JpaRepository<FunctionVersion, UUID> {
    Optional<FunctionVersion> findByFunctionDefinitionAndVersion(
            FunctionDefinition functionDefinition,
            int version
    );

    List<FunctionVersion> findByFunctionDefinitionOrderByVersionDesc(
            FunctionDefinition functionDefinition
    );

    long countByFunctionDefinition(FunctionDefinition functionDefinition);
}
