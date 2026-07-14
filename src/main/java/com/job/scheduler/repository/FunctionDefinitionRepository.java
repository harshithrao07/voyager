package com.job.scheduler.repository;

import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.enums.FunctionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FunctionDefinitionRepository
        extends JpaRepository<FunctionDefinition, UUID> {
    boolean existsByName(String name);

    Optional<FunctionDefinition> findByName(String name);

    List<FunctionDefinition> findAllByOrderByUpdatedAtDesc();

    List<FunctionDefinition> findByStatusNotOrderByUpdatedAtDesc(
            FunctionStatus status
    );
}
