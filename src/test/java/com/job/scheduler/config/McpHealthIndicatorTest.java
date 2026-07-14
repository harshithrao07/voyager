package com.job.scheduler.config;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.repository.McpToolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpHealthIndicatorTest {
    @Mock
    private McpServerRepository mcpServerRepository;
    @Mock
    private McpToolRepository mcpToolRepository;

    private Health health() {
        return new HealthIndicatorConfig()
                .mcpHealthIndicator(mcpServerRepository, mcpToolRepository)
                .health();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsUpWithPerServerToolCountsAndLastSeen() {
        McpServer crm = server("crm");
        Instant seen = Instant.parse("2026-07-13T10:00:00Z");
        when(mcpServerRepository.findByStatusOrderByCreatedAtDesc(McpServerStatus.ENABLED))
                .thenReturn(List.of(crm));
        when(mcpToolRepository.findByMcpServerAndEnabledTrueOrderByToolNameAsc(crm))
                .thenReturn(List.of(tool(crm, "get", seen), tool(crm, "list", null)));

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("enabledServers", 1);
        Map<String, Object> servers = (Map<String, Object>) health.getDetails().get("servers");
        Map<String, Object> crmDetail = (Map<String, Object>) servers.get("crm");
        assertThat(crmDetail).containsEntry("tools", 2);
        assertThat(crmDetail).containsEntry("lastSeenAt", seen);
    }

    @Test
    void reportsUpWithZeroServersWhenNoneEnabled() {
        when(mcpServerRepository.findByStatusOrderByCreatedAtDesc(McpServerStatus.ENABLED))
                .thenReturn(List.of());

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("enabledServers", 0);
    }

    private McpServer server(String serverId) {
        McpServer server = new McpServer();
        server.setServerId(serverId);
        server.setStatus(McpServerStatus.ENABLED);
        return server;
    }

    private McpTool tool(McpServer server, String name, Instant lastSeenAt) {
        McpTool tool = new McpTool();
        tool.setMcpServer(server);
        tool.setToolName(name);
        tool.setEnabled(true);
        tool.setLastSeenAt(lastSeenAt);
        return tool;
    }
}
