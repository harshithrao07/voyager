package com.job.scheduler.repository;

import com.job.scheduler.entity.Workflow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository
        extends JpaRepository<Workflow, UUID>,
        JpaSpecificationExecutor<Workflow> {
    Optional<Workflow> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT workflow FROM Workflow workflow WHERE workflow.id = :workflowId")
    Optional<Workflow> findByIdForUpdate(@Param("workflowId") UUID workflowId);

    @Query(value = """
            SELECT *
            FROM workflows
            WHERE status = 'ACTIVE'
              AND next_run_at IS NOT NULL
              AND next_run_at <= :now
            ORDER BY next_run_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """, nativeQuery = true)
    List<Workflow> claimDueWorkflows(
            @Param("now") Instant now,
            @Param("limit") int limit
    );
}
