package com.job.scheduler.repository;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.StateExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StateExecutionRepository
        extends JpaRepository<StateExecution, UUID> {

    Optional<StateExecution> findByExecutionScopeAndSequenceNumber(
            ExecutionScope executionScope,
            long sequenceNumber
    );

    Optional<StateExecution> findFirstByExecutionScopeOrderBySequenceNumberDesc(
            ExecutionScope executionScope
    );

    Optional<StateExecution>
            findFirstByExecutionScopeAndStateNameOrderBySequenceNumberDesc(
            ExecutionScope executionScope,
            String stateName
    );

    List<StateExecution> findByExecutionScopeAndStateNameOrderBySequenceNumberAsc(
            ExecutionScope executionScope,
            String stateName
    );

    List<StateExecution> findByExecutionScopeOrderBySequenceNumberAsc(
            ExecutionScope executionScope
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT stateExecution
            FROM StateExecution stateExecution
            WHERE stateExecution.id = :stateExecutionId
            """)
    Optional<StateExecution> findByIdForUpdate(
            @Param("stateExecutionId") UUID stateExecutionId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM state_executions state_execution
            USING execution_scopes scope
            WHERE state_execution.execution_scope_id = scope.id
              AND scope.workflow_execution_id IN (:executionIds)
            """, nativeQuery = true)
    int deleteByWorkflowExecutionIds(
            @Param("executionIds") List<UUID> executionIds
    );
}
