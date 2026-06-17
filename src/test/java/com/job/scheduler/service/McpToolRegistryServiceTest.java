package com.job.scheduler.service;

import com.job.scheduler.dto.McpToolResponseDTO;
import com.job.scheduler.dto.McpToolSyncResultDTO;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.repository.McpToolRepository;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolRegistryServiceTest {
    @Mock
    private McpServerRepository mcpServerRepository;

    @Mock
    private McpToolRepository mcpToolRepository;

    @Mock
    private McpClientService mcpClientService;

    private ObjectMapper objectMapper;
    private McpToolRegistryService mcpToolRegistryService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mcpToolRegistryService = new McpToolRegistryService(
                mcpServerRepository,
                mcpToolRepository,
                mcpClientService,
                objectMapper
        );
    }

    @Test
    void syncToolsCreatesNewToolsAndDisablesMissingTools() throws Exception {
        McpServer server = server();
        McpTool oldTool = tool(server, "old-tool", true);
        McpSchema.Tool discoveredTool = McpSchema.Tool.builder("ping", Map.of("type", "object"))
                .title("Ping")
                .description("Ping tool")
                .build();

        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpClientService.listTools("local-tools"))
                .thenReturn(Mono.just(new McpSchema.ListToolsResult(List.of(discoveredTool), null)));
        when(mcpToolRepository.findByMcpServerOrderByToolNameAsc(server))
                .thenReturn(List.of(oldTool))
                .thenAnswer(invocation -> List.of(oldTool, savedToolFromCaptor()));
        when(mcpToolRepository.save(any(McpTool.class))).thenAnswer(invocation -> {
            McpTool tool = invocation.getArgument(0);
            if (tool.getId() == null) {
                tool.setId(UUID.randomUUID());
            }
            tool.setCreatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            tool.setUpdatedAt(Instant.parse("2026-06-17T00:00:00Z"));
            return tool;
        });

        McpToolSyncResultDTO result = mcpToolRegistryService.syncTools("local-tools");

        assertThat(result.serverId()).isEqualTo("local-tools");
        assertThat(result.discoveredCount()).isEqualTo(1);
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isEqualTo(0);
        assertThat(result.disabledCount()).isEqualTo(1);
        assertThat(oldTool.isEnabled()).isFalse();

        ArgumentCaptor<McpTool> toolCaptor = ArgumentCaptor.forClass(McpTool.class);
        verify(mcpToolRepository, atLeastOnce()).save(toolCaptor.capture());
        McpTool savedDiscoveredTool = toolCaptor.getAllValues()
                .stream()
                .filter(tool -> "ping".equals(tool.getToolName()))
                .findFirst()
                .orElseThrow();

        assertThat(savedDiscoveredTool.getTitle()).isEqualTo("Ping");
        assertThat(savedDiscoveredTool.getDescription()).isEqualTo("Ping tool");
        assertThat(objectMapper.readTree(savedDiscoveredTool.getInputSchema()).get("type").stringValue())
                .isEqualTo("object");
        assertThat(savedDiscoveredTool.isEnabled()).isTrue();
        assertThat(savedDiscoveredTool.getLastSeenAt()).isNotNull();
    }

    @Test
    void syncToolsUpdatesExistingTool() {
        McpServer server = server();
        McpTool existingTool = tool(server, "ping", false);
        McpSchema.Tool discoveredTool = McpSchema.Tool.builder("ping", Map.of("type", "object"))
                .description("Updated")
                .build();

        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpClientService.listTools("local-tools"))
                .thenReturn(Mono.just(new McpSchema.ListToolsResult(List.of(discoveredTool), null)));
        when(mcpToolRepository.findByMcpServerOrderByToolNameAsc(server)).thenReturn(List.of(existingTool));
        when(mcpToolRepository.save(any(McpTool.class))).thenAnswer(invocation -> invocation.getArgument(0));

        McpToolSyncResultDTO result = mcpToolRegistryService.syncTools("local-tools");

        assertThat(result.createdCount()).isZero();
        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.disabledCount()).isZero();
        assertThat(existingTool.getDescription()).isEqualTo("Updated");
        assertThat(existingTool.isEnabled()).isTrue();
    }

    @Test
    void getKnownToolsCanReturnEnabledOnlyTools() {
        McpServer server = server();
        McpTool tool = tool(server, "ping", true);

        when(mcpServerRepository.findByServerId("local-tools")).thenReturn(Optional.of(server));
        when(mcpToolRepository.findByMcpServerAndEnabledTrueOrderByToolNameAsc(server)).thenReturn(List.of(tool));

        List<McpToolResponseDTO> result = mcpToolRegistryService.getKnownTools("local-tools", true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).toolName()).isEqualTo("ping");
        assertThat(result.get(0).inputSchema().get("type").stringValue()).isEqualTo("object");
    }

    @Test
    void getKnownToolsThrowsWhenServerDoesNotExist() {
        when(mcpServerRepository.findByServerId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mcpToolRegistryService.getKnownTools("missing", false))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("MCP server does not exist");
    }

    private McpTool savedToolFromCaptor() {
        McpTool tool = tool(server(), "ping", true);
        tool.setTitle("Ping");
        tool.setDescription("Ping tool");
        tool.setLastSeenAt(Instant.parse("2026-06-17T00:00:00Z"));
        return tool;
    }

    private McpServer server() {
        McpServer server = new McpServer();
        server.setId(UUID.randomUUID());
        server.setServerId("local-tools");
        server.setDisplayName("Local Tools");
        server.setBaseUrl("http://localhost:8081");
        server.setEndpoint("/mcp");
        server.setTransport(McpTransport.HTTP);
        server.setAuthType(McpAuthType.NONE);
        server.setTrustLevel(McpTrustLevel.READ_ONLY);
        server.setStatus(McpServerStatus.ENABLED);
        return server;
    }

    private McpTool tool(McpServer server, String toolName, boolean enabled) {
        McpTool tool = new McpTool();
        tool.setId(UUID.randomUUID());
        tool.setMcpServer(server);
        tool.setToolName(toolName);
        tool.setDescription(toolName + " description");
        tool.setInputSchema("{\"type\":\"object\"}");
        tool.setEnabled(enabled);
        tool.setLastSeenAt(Instant.parse("2026-06-17T00:00:00Z"));
        tool.setCreatedAt(Instant.parse("2026-06-17T00:00:00Z"));
        tool.setUpdatedAt(Instant.parse("2026-06-17T00:00:00Z"));
        return tool;
    }
}
