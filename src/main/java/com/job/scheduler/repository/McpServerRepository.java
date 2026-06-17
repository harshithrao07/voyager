package com.job.scheduler.repository;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpServerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface McpServerRepository extends JpaRepository<McpServer, UUID> {
    Optional<McpServer> findByServerId(String serverId);

    boolean existsByServerId(String serverId);

    List<McpServer> findByStatusOrderByCreatedAtDesc(McpServerStatus status);

    List<McpServer> findAllByOrderByCreatedAtDesc();
}
