package com.job.scheduler.repository;

import com.job.scheduler.entity.McpToolExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface McpToolExecutionRepository extends JpaRepository<McpToolExecution, UUID> {
    List<McpToolExecution> findByServerIdOrderByStartedAtDesc(String serverId);

    List<McpToolExecution> findByServerIdAndToolNameOrderByStartedAtDesc(String serverId, String toolName);
}
