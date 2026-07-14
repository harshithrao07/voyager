package com.job.scheduler.scheduler;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.service.McpToolRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolSyncSchedulerServiceTest {
    @Mock
    private McpServerRepository mcpServerRepository;
    @Mock
    private McpToolRegistryService mcpToolRegistryService;

    @InjectMocks
    private McpToolSyncSchedulerService scheduler;

    @Test
    void syncsEveryEnabledServer() {
        when(mcpServerRepository.findByStatusOrderByCreatedAtDesc(McpServerStatus.ENABLED))
                .thenReturn(List.of(server("crm"), server("github")));

        scheduler.resyncEnabledServers();

        verify(mcpToolRegistryService).syncTools("crm");
        verify(mcpToolRegistryService).syncTools("github");
    }

    @Test
    void continuesSyncingAfterAServerFails() {
        when(mcpServerRepository.findByStatusOrderByCreatedAtDesc(McpServerStatus.ENABLED))
                .thenReturn(List.of(server("broken"), server("healthy")));
        when(mcpToolRegistryService.syncTools("broken"))
                .thenThrow(new IllegalStateException("unreachable"));

        scheduler.resyncEnabledServers();

        // The failing server must not stall the rest of the batch.
        verify(mcpToolRegistryService).syncTools(eq("healthy"));
    }

    private McpServer server(String serverId) {
        McpServer server = new McpServer();
        server.setServerId(serverId);
        server.setStatus(McpServerStatus.ENABLED);
        return server;
    }
}
