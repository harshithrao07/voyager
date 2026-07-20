package com.job.scheduler.controller;

import com.job.scheduler.dto.McpServerResponseDTO;
import com.job.scheduler.dto.McpServerRequestDTO;
import com.job.scheduler.dto.McpToolExecutionResponseDTO;
import com.job.scheduler.dto.McpToolResponseDTO;
import com.job.scheduler.dto.McpToolSyncResultDTO;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpToolExecutionStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.exception.ApiExceptionHandler;
import com.job.scheduler.service.McpClientService;
import com.job.scheduler.service.McpServerRegistryService;
import com.job.scheduler.service.McpToolExecutionService;
import com.job.scheduler.service.McpToolRegistryService;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class McpServerControllerTest {
    @Mock
    private McpServerRegistryService mcpServerRegistryService;

    @Mock
    private McpClientService mcpClientService;

    @Mock
    private McpToolRegistryService mcpToolRegistryService;

    @Mock
    private McpToolExecutionService mcpToolExecutionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new McpServerController(
                        mcpServerRegistryService,
                        mcpClientService,
                        mcpToolRegistryService,
                        mcpToolExecutionService
                ))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void registerServerReturnsRegisteredServer() throws Exception {
        McpServerResponseDTO response = response("local-tools", McpServerStatus.DISABLED);
        when(mcpServerRegistryService.registerServer(any())).thenReturn(response);

        String body = """
                {
                  "serverId": "local-tools",
                  "displayName": "Local Tools",
                  "baseUrl": "http://localhost:8081",
                  "endpoint": "/mcp",
                  "transport": "HTTP",
                  "authType": "NONE"
                }
                """;

        mockMvc.perform(post("/app/v1/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverId").value("local-tools"))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.trustLevel").value("READ_ONLY"));
    }

    @Test
    void registerServerValidatesRequestBody() throws Exception {
        String body = """
                {
                  "serverId": "Bad Server",
                  "displayName": "",
                  "baseUrl": "http://localhost:8081",
                  "endpoint": "mcp",
                  "transport": "HTTP",
                  "authType": "NONE"
                }
                """;

        mockMvc.perform(post("/app/v1/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3));
    }

    @Test
    void registerServerAcceptsWriteOnlyCustomAuthenticationHeaders() throws Exception {
        McpServerResponseDTO response = new McpServerResponseDTO(
                UUID.randomUUID(), "multi-auth", "Multi Auth", "https://mcp.example.com",
                "/mcp", null, List.of(), Map.of(), java.util.Set.of(),
                java.util.Set.of("X-API-Key", "X-Client-Secret"), null,
                McpTransport.HTTP, McpAuthType.CUSTOM_HEADERS, false, null, null,
                McpTrustLevel.READ_ONLY, McpServerStatus.DISABLED, null,
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:00Z")
        );
        when(mcpServerRegistryService.registerServer(any())).thenReturn(response);

        String body = """
                {
                  "serverId": "multi-auth",
                  "displayName": "Multi Auth",
                  "baseUrl": "https://mcp.example.com",
                  "endpoint": "/mcp",
                  "transport": "HTTP",
                  "authType": "CUSTOM_HEADERS",
                  "secretHeaders": {
                    "X-API-Key": "secret-one",
                    "X-Client-Secret": "secret-two"
                  }
                }
                """;

        mockMvc.perform(post("/app/v1/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authType").value("CUSTOM_HEADERS"))
                .andExpect(jsonPath("$.hasAuthToken").value(false))
                .andExpect(jsonPath("$.secretHeaderNames.length()").value(2))
                .andExpect(jsonPath("$.secretHeaders").doesNotExist());

        ArgumentCaptor<McpServerRequestDTO> requestCaptor =
                ArgumentCaptor.forClass(McpServerRequestDTO.class);
        verify(mcpServerRegistryService).registerServer(requestCaptor.capture());
        assertThat(requestCaptor.getValue().secretHeaders())
                .containsEntry("X-API-Key", "secret-one")
                .containsEntry("X-Client-Secret", "secret-two");
    }

    @Test
    void getServersPassesStatusFilter() throws Exception {
        when(mcpServerRegistryService.getServers(McpServerStatus.ENABLED))
                .thenReturn(List.of(response("local-tools", McpServerStatus.ENABLED)));

        mockMvc.perform(get("/app/v1/mcp/servers").param("status", "ENABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].serverId").value("local-tools"))
                .andExpect(jsonPath("$[0].status").value("ENABLED"));
    }

    @Test
    void getServerReturnsNotFoundWhenMissing() throws Exception {
        when(mcpServerRegistryService.getServer("missing"))
                .thenThrow(new EntityNotFoundException("MCP server does not exist"));

        mockMvc.perform(get("/app/v1/mcp/servers/{serverId}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("MCP server does not exist"));
    }

    @Test
    void updateStatusReturnsUpdatedServer() throws Exception {
        when(mcpServerRegistryService.updateStatus(eq("local-tools"), eq(McpServerStatus.ENABLED)))
                .thenReturn(response("local-tools", McpServerStatus.ENABLED));

        String body = """
                {
                  "status": "ENABLED"
                }
                """;

        mockMvc.perform(patch("/app/v1/mcp/servers/{serverId}/status", "local-tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverId").value("local-tools"))
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }

    @Test
    void listToolsUsesRegistryBackedClient() throws Exception {
        McpSchema.Tool tool = McpSchema.Tool.builder("ping", Map.of("type", "object")).build();
        when(mcpClientService.listTools("local-tools"))
                .thenReturn(reactor.core.publisher.Mono.just(new McpSchema.ListToolsResult(List.of(tool), null)));

        var mvcResult = mockMvc.perform(get("/app/v1/mcp/servers/{serverId}/tools", "local-tools"))
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andExpect(jsonPath("$.tools[0].name").value("ping"));
    }

    @Test
    void getKnownToolsReturnsPersistedTools() throws Exception {
        when(mcpToolRegistryService.getKnownTools("local-tools", true))
                .thenReturn(List.of(toolResponse("local-tools", "ping", true)));

        mockMvc.perform(get("/app/v1/mcp/servers/{serverId}/tools/known", "local-tools")
                        .param("enabledOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].serverId").value("local-tools"))
                .andExpect(jsonPath("$[0].toolName").value("ping"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void syncToolsReturnsPersistedInventory() throws Exception {
        McpToolResponseDTO tool = toolResponse("local-tools", "ping", true);
        when(mcpToolRegistryService.syncTools("local-tools"))
                .thenReturn(new McpToolSyncResultDTO(
                        "local-tools",
                        1,
                        1,
                        0,
                        0,
                        Instant.parse("2026-06-17T00:00:00Z"),
                        List.of(tool)
                ));

        mockMvc.perform(post("/app/v1/mcp/servers/{serverId}/tools/sync", "local-tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverId").value("local-tools"))
                .andExpect(jsonPath("$.discoveredCount").value(1))
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.tools[0].toolName").value("ping"));
    }

    @Test
    void getExecutionsReturnsToolHistory() throws Exception {
        when(mcpToolExecutionService.getExecutions("local-tools", "ping"))
                .thenReturn(List.of(executionResponse("local-tools", "ping")));

        mockMvc.perform(get("/app/v1/mcp/servers/{serverId}/executions", "local-tools")
                        .param("toolName", "ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].serverId").value("local-tools"))
                .andExpect(jsonPath("$[0].toolName").value("ping"))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"));
    }

    @Test
    void callToolUsesSafeExecutionService() throws Exception {
        when(mcpToolExecutionService.callTool(
                eq("local-tools"),
                eq("ping"),
                any(),
                eq(McpTrustLevel.READ_ONLY)
        ))
                .thenReturn(reactor.core.publisher.Mono.just(
                        new McpSchema.CallToolResult(List.of(), false, Map.of("ok", true), Map.of())
                ));

        String body = """
                {
                  "arguments": {
                    "message": "hello"
                  },
                  "maxAllowedTrustLevel": "READ_ONLY"
                }
                """;

        var mvcResult = mockMvc.perform(post("/app/v1/mcp/servers/{serverId}/tools/{toolName}/call", "local-tools", "ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isError").value(false))
                .andExpect(jsonPath("$.structuredContent.ok").value(true));
    }

    private McpServerResponseDTO response(String serverId, McpServerStatus status) {
        return new McpServerResponseDTO(
                UUID.randomUUID(),
                serverId,
                "Local Tools",
                "http://localhost:8081",
                "/mcp",
                null,
                null,
                null,
                java.util.Set.of(),
                java.util.Set.of(),
                null,
                McpTransport.HTTP,
                McpAuthType.NONE,
                false,
                null,
                null,
                McpTrustLevel.READ_ONLY,
                status,
                null,
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:00Z")
        );
    }

    private McpToolResponseDTO toolResponse(String serverId, String toolName, boolean enabled) {
        tools.jackson.databind.ObjectMapper objectMapper = new tools.jackson.databind.ObjectMapper();
        return new McpToolResponseDTO(
                UUID.randomUUID(),
                serverId,
                toolName,
                "Ping",
                "Ping tool",
                objectMapper.createObjectNode().put("type", "object"),
                null,
                enabled,
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:00Z")
        );
    }

    private McpToolExecutionResponseDTO executionResponse(String serverId, String toolName) {
        tools.jackson.databind.ObjectMapper objectMapper = new tools.jackson.databind.ObjectMapper();
        return new McpToolExecutionResponseDTO(
                UUID.randomUUID(),
                serverId,
                toolName,
                objectMapper.createObjectNode().put("message", "hello"),
                objectMapper.createObjectNode().put("ok", true),
                McpToolExecutionStatus.SUCCESS,
                McpTrustLevel.READ_ONLY,
                null,
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:01Z"),
                1000L,
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:01Z")
        );
    }
}
