package com.job.scheduler.scheduler;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.service.McpToolRegistryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Periodically re-syncs the persisted tool catalog for every ENABLED MCP server
 * so it does not drift from what each server actually advertises. Manual syncs
 * (POST .../tools/sync) still work; this is the background safety net.
 *
 * <p>Each server is synced independently: an unreachable or failing server is
 * logged and skipped so it cannot stall the rest of the batch.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class McpToolSyncSchedulerService {
    private static final Logger log =
            LoggerFactory.getLogger(McpToolSyncSchedulerService.class);

    private final McpServerRepository mcpServerRepository;
    private final McpToolRegistryService mcpToolRegistryService;

    @Scheduled(
            fixedDelayString = "${scheduler.mcp.tool-sync-delay-ms:900000}",
            initialDelayString = "${scheduler.mcp.tool-sync-initial-delay-ms:60000}"
    )
    public void resyncEnabledServers() {
        List<McpServer> servers = mcpServerRepository
                .findByStatusOrderByCreatedAtDesc(McpServerStatus.ENABLED);
        for (McpServer server : servers) {
            String serverId = server.getServerId();
            try {
                mcpToolRegistryService.syncTools(serverId);
            } catch (RuntimeException exception) {
                log.warn(
                        "Scheduled MCP tool sync failed for {}: {}",
                        serverId,
                        exception.getMessage()
                );
            }
        }
    }
}
