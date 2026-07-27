package com.job.scheduler.repository;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface McpToolRepository extends JpaRepository<McpTool, UUID> {
    List<McpTool> findByMcpServerOrderByToolNameAsc(McpServer mcpServer);

    List<McpTool> findByMcpServerAndEnabledTrueOrderByToolNameAsc(McpServer mcpServer);

    @EntityGraph(attributePaths = "mcpServer")
    List<McpTool> findByEnabledTrue();

    Optional<McpTool> findByMcpServerAndToolName(McpServer mcpServer, String toolName);
}
