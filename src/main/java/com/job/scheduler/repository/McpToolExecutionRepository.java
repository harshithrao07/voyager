package com.job.scheduler.repository;

import com.job.scheduler.entity.McpToolExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface McpToolExecutionRepository extends JpaRepository<McpToolExecution, UUID> {
    List<McpToolExecution> findByServerIdOrderByStartedAtDesc(String serverId);

    List<McpToolExecution> findByServerIdAndToolNameOrderByStartedAtDesc(String serverId, String toolName);

    @Query("""
            SELECT mcpToolExecution
            FROM McpToolExecution mcpToolExecution
            JOIN FETCH mcpToolExecution.stepExecution stepExecution
            WHERE stepExecution.id IN :stepExecutionIds
            """)
    List<McpToolExecution> findByStepExecutionIds(@Param("stepExecutionIds") List<UUID> stepExecutionIds);
}
