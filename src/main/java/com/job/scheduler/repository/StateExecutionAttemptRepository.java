package com.job.scheduler.repository;

import com.job.scheduler.entity.StateExecution;
import com.job.scheduler.entity.StateExecutionAttempt;
import com.job.scheduler.enums.StateExecutionAttemptKind;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface StateExecutionAttemptRepository
        extends JpaRepository<StateExecutionAttempt, UUID> {

    Optional<StateExecutionAttempt> findByStateExecutionAndAttemptNumber(
            StateExecution stateExecution,
            int attemptNumber
    );

    Optional<StateExecutionAttempt>
    findFirstByStateExecutionOrderByAttemptNumberDesc(
            StateExecution stateExecution
    );

    Optional<StateExecutionAttempt>
    findFirstByStateExecutionAndKindOrderByAttemptNumberDesc(
            StateExecution stateExecution,
            StateExecutionAttemptKind kind
    );

    List<StateExecutionAttempt> findByStateExecutionOrderByAttemptNumberAsc(
            StateExecution stateExecution
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT attempt
            FROM StateExecutionAttempt attempt
            WHERE attempt.id = :attemptId
            """)
    Optional<StateExecutionAttempt> findByIdForUpdate(
            @Param("attemptId") UUID attemptId
    );

    @Query(value = """
            UPDATE state_execution_attempts
            SET status = 'QUEUED',
                queued_at = :now,
                dispatch_attempt_count = dispatch_attempt_count + 1,
                last_dispatch_error = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN (
                SELECT id
                FROM state_execution_attempts
                WHERE status = 'PENDING'
                  AND COALESCE(available_at, created_at) <= :now
                ORDER BY COALESCE(available_at, created_at) ASC
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
            )
            RETURNING *
            """, nativeQuery = true)
    List<StateExecutionAttempt> claimDueAttemptsForDispatch(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    @Query(value = """
            UPDATE state_execution_attempts
            SET status = 'PENDING',
                available_at = :now,
                queued_at = NULL,
                last_dispatch_error =
                    'Recovered stale QUEUED task attempt',
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN (
                SELECT id
                FROM state_execution_attempts
                WHERE status = 'QUEUED'
                  AND queued_at <= :cutoff
                ORDER BY queued_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
            )
            RETURNING *
            """, nativeQuery = true)
    List<StateExecutionAttempt> recoverStaleQueuedAttempts(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    List<StateExecutionAttempt> findByStatusAndTimeoutAtLessThanEqual(
            com.job.scheduler.enums.StateExecutionAttemptStatus status,
            Instant cutoff
    );

    List<StateExecutionAttempt>
    findByStatusAndHeartbeatDeadlineAtLessThanEqual(
            com.job.scheduler.enums.StateExecutionAttemptStatus status,
            Instant cutoff
    );

    @Query("""
            SELECT attempt
            FROM StateExecutionAttempt attempt
            WHERE attempt.status = :status
              AND attempt.timeoutAt IS NULL
              AND attempt.heartbeatDeadlineAt IS NULL
              AND attempt.startedAt <= :cutoff
            """)
    List<StateExecutionAttempt> findRunningWithoutAslDeadlineBefore(
            @Param("status")
            com.job.scheduler.enums.StateExecutionAttemptStatus status,
            @Param("cutoff") Instant cutoff
    );

    @Query("""
            UPDATE StateExecutionAttempt attempt
            SET attempt.status = :runningStatus,
                attempt.workerId = :workerId,
                attempt.startedAt = :now,
                attempt.heartbeatAt = :now
            WHERE attempt.id = :attemptId
              AND attempt.status = :queuedStatus
            """)
    @org.springframework.data.jpa.repository.Modifying
    int claimQueuedAttemptForExecution(
            @Param("attemptId") UUID attemptId,
            @Param("workerId") String workerId,
            @Param("now") Instant now,
            @Param("queuedStatus")
            com.job.scheduler.enums.StateExecutionAttemptStatus queuedStatus,
            @Param("runningStatus")
            com.job.scheduler.enums.StateExecutionAttemptStatus runningStatus
    );

    @Query("""
            UPDATE StateExecutionAttempt attempt
            SET attempt.heartbeatAt = :now,
                attempt.heartbeatDeadlineAt = :heartbeatDeadline
            WHERE attempt.id = :attemptId
              AND attempt.status = :runningStatus
              AND attempt.workerId = :workerId
            """)
    @org.springframework.data.jpa.repository.Modifying
    int recordHeartbeat(
            @Param("attemptId") UUID attemptId,
            @Param("workerId") String workerId,
            @Param("now") Instant now,
            @Param("heartbeatDeadline") Instant heartbeatDeadline,
            @Param("runningStatus")
            com.job.scheduler.enums.StateExecutionAttemptStatus runningStatus
    );
}
