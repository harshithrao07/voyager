package com.job.scheduler.repository;

import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowExecutionRepository
        extends JpaRepository<WorkflowExecution, UUID> {

    Optional<WorkflowExecution> findByWorkflowAndScheduledFor(
            Workflow workflow,
            Instant scheduledFor
    );

    Optional<WorkflowExecution> findFirstByWorkflowOrderByRunNumberDesc(
            Workflow workflow
    );

    List<WorkflowExecution> findByWorkflowOrderByRunNumberDesc(
            Workflow workflow
    );

    Page<WorkflowExecution> findByWorkflowOrderByRunNumberDesc(
            Workflow workflow,
            Pageable pageable
    );

    @Query("""
            SELECT execution
            FROM WorkflowExecution execution
            WHERE execution.workflow = :workflow
              AND (:status IS NULL OR execution.status = :status)
              AND (:revision IS NULL
                    OR execution.workflowDefinition.revision = :revision)
              AND (:scheduled IS NULL
                    OR (:scheduled = TRUE
                        AND execution.scheduledFor IS NOT NULL)
                    OR (:scheduled = FALSE
                        AND execution.scheduledFor IS NULL))
              AND (:searchProvided = FALSE
                    OR execution.id = :executionId
                    OR execution.runNumber = :runNumber)
            ORDER BY execution.runNumber DESC
            """)
    Page<WorkflowExecution> findFiltered(
            @Param("workflow") Workflow workflow,
            @Param("status") WorkflowExecutionStatus status,
            @Param("revision") Long revision,
            @Param("scheduled") Boolean scheduled,
            @Param("searchProvided") boolean searchProvided,
            @Param("executionId") UUID executionId,
            @Param("runNumber") Long runNumber,
            Pageable pageable
    );

    @Query(value = """
            SELECT id
            FROM workflow_executions
            WHERE status IN (
                    'SUCCEEDED', 'FAILED', 'CANCELED', 'TIMED_OUT'
                  )
              AND completed_at IS NOT NULL
              AND completed_at <= :cutoff
            ORDER BY completed_at ASC, id ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> claimExpiredTerminalExecutionIds(
            @Param("cutoff") Instant cutoff,
            @Param("limit") int limit
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM workflow_executions
            WHERE id IN (:executionIds)
              AND status IN (
                    'SUCCEEDED', 'FAILED', 'CANCELED', 'TIMED_OUT'
                  )
              AND completed_at IS NOT NULL
              AND completed_at <= :cutoff
            """, nativeQuery = true)
    int deleteRetainedExecutions(
            @Param("executionIds") List<UUID> executionIds,
            @Param("cutoff") Instant cutoff
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT execution
            FROM WorkflowExecution execution
            WHERE execution.id = :executionId
            """)
    Optional<WorkflowExecution> findByIdForUpdate(
            @Param("executionId") UUID executionId
    );

    List<WorkflowExecution> findByDeadlineAtLessThanEqualAndStatusIn(
            Instant deadline,
            List<com.job.scheduler.enums.WorkflowExecutionStatus> statuses,
            Pageable pageable
    );
}
