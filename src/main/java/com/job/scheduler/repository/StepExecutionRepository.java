package com.job.scheduler.repository;

import com.job.scheduler.entity.StepExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StepExecutionRepository extends JpaRepository<StepExecution, UUID> {
    List<StepExecution> findByExecutionLogIdOrderByStepOrderAsc(UUID executionLogId);
}
