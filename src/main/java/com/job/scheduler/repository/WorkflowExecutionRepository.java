package com.job.scheduler.repository;

import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
