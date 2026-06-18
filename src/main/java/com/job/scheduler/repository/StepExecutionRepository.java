package com.job.scheduler.repository;

import com.job.scheduler.entity.StepExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StepExecutionRepository extends JpaRepository<StepExecution, UUID> {
    @Query("""
            SELECT stepExecution
            FROM StepExecution stepExecution
            JOIN FETCH stepExecution.executionLog executionLog
            JOIN FETCH stepExecution.jobStep
            WHERE executionLog.id IN :executionLogIds
            ORDER BY executionLog.id ASC, stepExecution.stepOrder ASC
            """)
    List<StepExecution> findByExecutionLogIdsOrderByExecutionLogIdAndStepOrder(
            @Param("executionLogIds") List<UUID> executionLogIds
    );
}
