package com.job.scheduler.repository;

import com.job.scheduler.entity.ExecutionScope;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.ExecutionScopeType;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface ExecutionScopeRepository
        extends JpaRepository<ExecutionScope, UUID> {

    Optional<ExecutionScope> findByWorkflowExecutionAndScopePath(
            WorkflowExecution workflowExecution,
            String scopePath
    );

    Optional<ExecutionScope> findByWorkflowExecutionAndScopeType(
            WorkflowExecution workflowExecution,
            ExecutionScopeType scopeType
    );

    List<ExecutionScope> findByParentScopeOrderByScopePathAsc(
            ExecutionScope parentScope
    );

    List<ExecutionScope> findByParentScopeAndScopePathStartingWithOrderByScopePathAsc(
            ExecutionScope parentScope,
            String scopePathPrefix
    );

    List<ExecutionScope> findByWorkflowExecutionOrderByScopePathAsc(
            WorkflowExecution workflowExecution
    );

    List<ExecutionScope>
    findByWorkflowExecutionAndScopePathStartingWithOrderByScopePathAsc(
            WorkflowExecution workflowExecution,
            String scopePathPrefix
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT scope
            FROM ExecutionScope scope
            WHERE scope.id = :scopeId
            """)
    Optional<ExecutionScope> findByIdForUpdate(@Param("scopeId") UUID scopeId);

    @Query(value = """
            UPDATE execution_scopes
            SET status = 'RUNNING',
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN (
                SELECT id
                FROM execution_scopes
                WHERE wake_at <= :now
                  AND (
                    status = 'WAITING'
                    OR (
                      status = 'RUNNING'
                      AND updated_at <= :staleBefore
                    )
                  )
                ORDER BY wake_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
            )
            RETURNING *
            """, nativeQuery = true)
    @Transactional
    List<ExecutionScope> claimDueWaitingScopes(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            @Param("limit") int limit
    );

    @Query(value = """
            UPDATE execution_scopes
            SET status = 'RUNNING',
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN (
                SELECT scope.id
                FROM execution_scopes scope
                JOIN workflow_executions execution
                  ON execution.id = scope.workflow_execution_id
                WHERE scope.scope_type = 'ROOT'
                  AND execution.status = 'PENDING'
                  AND (
                    scope.status = 'PENDING'
                    OR (
                      scope.status = 'RUNNING'
                      AND scope.updated_at <= :staleBefore
                    )
                  )
                ORDER BY COALESCE(execution.scheduled_for, execution.created_at) ASC
                FOR UPDATE OF scope SKIP LOCKED
                LIMIT :limit
            )
            RETURNING *
            """, nativeQuery = true)
    @Transactional
    List<ExecutionScope> claimPendingExecutionRoots(
            @Param("staleBefore") Instant staleBefore,
            @Param("limit") int limit
    );

    /**
     * Claims scopes that are durably runnable but were not driven after the
     * transaction that made them runnable committed.
     *
     * <p>A latest SUCCEEDED visit means a simple state or Task already moved
     * the cursor to its next state. A latest RUNNING Parallel/Map visit means a
     * compound parent was made runnable for a join/progress pass. Active Task
     * visits are deliberately excluded.
     */
    @Query(value = """
            UPDATE execution_scopes
            SET updated_at = CURRENT_TIMESTAMP
            WHERE id IN (
                SELECT scope.id
                FROM execution_scopes scope
                JOIN workflow_executions execution
                  ON execution.id = scope.workflow_execution_id
                WHERE scope.status = 'RUNNING'
                  AND scope.current_state_name IS NOT NULL
                  AND scope.wake_at IS NULL
                  AND scope.updated_at <= :staleBefore
                  AND execution.status = 'RUNNING'
                  AND EXISTS (
                    SELECT 1
                    FROM state_executions latest
                    WHERE latest.execution_scope_id = scope.id
                      AND (
                        latest.status = 'SUCCEEDED'
                        OR (
                          latest.status = 'RUNNING'
                          AND latest.state_type IN ('PARALLEL', 'MAP')
                        )
                      )
                      AND NOT EXISTS (
                        SELECT 1
                        FROM state_executions newer
                        WHERE newer.execution_scope_id = scope.id
                          AND newer.sequence_number > latest.sequence_number
                      )
                  )
                ORDER BY scope.updated_at ASC
                FOR UPDATE OF scope SKIP LOCKED
                LIMIT :limit
            )
            RETURNING *
            """, nativeQuery = true)
    @Transactional
    List<ExecutionScope> claimStaleRunnableScopes(
            @Param("staleBefore") Instant staleBefore,
            @Param("limit") int limit
    );

    /**
     * Claims terminal child scopes whose completion notification may have been
     * lost after commit. The child must belong to the latest Parallel/Map fork
     * generation of a still-waiting parent. A failed Parallel branch is enough
     * to notify immediately; otherwise every sibling in the generation must be
     * terminal.
     */
    @Query(value = """
            UPDATE execution_scopes
            SET updated_at = CURRENT_TIMESTAMP
            WHERE id IN (
                SELECT child.id
                FROM execution_scopes child
                JOIN execution_scopes parent
                  ON parent.id = child.parent_scope_id
                JOIN workflow_executions execution
                  ON execution.id = child.workflow_execution_id
                JOIN state_executions latest
                  ON latest.execution_scope_id = parent.id
                 AND latest.state_name = parent.current_state_name
                 AND latest.status = 'RUNNING'
                 AND latest.state_type IN ('PARALLEL', 'MAP')
                 AND NOT EXISTS (
                    SELECT 1
                    FROM state_executions newer
                    WHERE newer.execution_scope_id = parent.id
                      AND newer.sequence_number > latest.sequence_number
                 )
                WHERE child.status IN (
                        'SUCCEEDED', 'FAILED', 'CANCELED', 'TIMED_OUT'
                      )
                  AND child.updated_at <= :staleBefore
                  AND parent.status = 'WAITING'
                  AND execution.status IN ('RUNNING', 'WAITING', 'QUEUED')
                  AND child.scope_path LIKE
                        '%/g' || latest.sequence_number || '/%'
                  AND (
                    (
                      latest.state_type = 'PARALLEL'
                      AND child.status IN ('FAILED', 'TIMED_OUT')
                    )
                    OR NOT EXISTS (
                      SELECT 1
                      FROM execution_scopes sibling
                      WHERE sibling.parent_scope_id = parent.id
                        AND sibling.scope_path LIKE
                              regexp_replace(
                                child.scope_path,
                                '[^/]+$',
                                ''
                              ) || '%'
                        AND sibling.status NOT IN (
                              'SUCCEEDED',
                              'FAILED',
                              'CANCELED',
                              'TIMED_OUT'
                            )
                    )
                  )
                ORDER BY child.updated_at ASC
                FOR UPDATE OF child SKIP LOCKED
                LIMIT :limit
            )
            RETURNING *
            """, nativeQuery = true)
    @Transactional
    List<ExecutionScope> claimStaleSettledChildren(
            @Param("staleBefore") Instant staleBefore,
            @Param("limit") int limit
    );

    @Query("""
            SELECT scope
            FROM ExecutionScope scope
            WHERE scope.scopeType <> :rootType
              AND scope.startedAt IS NOT NULL
              AND scope.startedAt <= :startedBefore
              AND scope.status IN :statuses
            ORDER BY scope.startedAt ASC
            """)
    List<ExecutionScope> findNestedTimeoutCandidates(
            @Param("rootType") ExecutionScopeType rootType,
            @Param("startedBefore") Instant startedBefore,
            @Param("statuses")
            List<com.job.scheduler.enums.ExecutionScopeStatus> statuses
    );
}
