package com.job.scheduler.controller;

import com.job.scheduler.dto.McpServerRequestDTO;
import com.job.scheduler.dto.McpServerResponseDTO;
import com.job.scheduler.dto.McpServerStatusUpdateDTO;
import com.job.scheduler.dto.McpToolCallRequestDTO;
import com.job.scheduler.dto.McpToolExecutionResponseDTO;
import com.job.scheduler.dto.McpToolResponseDTO;
import com.job.scheduler.dto.McpToolSyncResultDTO;
import com.job.scheduler.dto.PublicMcpServerDTO;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.service.McpClientService;
import com.job.scheduler.service.McpServerRegistryService;
import com.job.scheduler.service.McpToolExecutionService;
import com.job.scheduler.service.McpToolRegistryService;
import com.job.scheduler.service.PublicMcpRegistryService;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/app/v1/mcp/servers")
public class McpServerController {
    private final McpServerRegistryService mcpServerRegistryService;
    private final McpClientService mcpClientService;
    private final McpToolRegistryService mcpToolRegistryService;
    private final McpToolExecutionService mcpToolExecutionService;
    private final PublicMcpRegistryService publicMcpRegistryService;

    @PostMapping
    public ResponseEntity<McpServerResponseDTO> registerServer(
            @Valid @RequestBody McpServerRequestDTO request
    ) {
        return ResponseEntity.ok(mcpServerRegistryService.registerServer(request));
    }

    /**
     * Searches the public MCP catalog (bundled, plus the external registry when enabled)
     * for servers matching a capability. Read-only recommendations — the caller registers
     * a chosen server through {@link #registerServer}.
     */
    @GetMapping("/registry/search")
    public ResponseEntity<List<PublicMcpServerDTO>> searchRegistry(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(publicMcpRegistryService.search(query, limit));
    }

    @GetMapping
    public ResponseEntity<List<McpServerResponseDTO>> getServers(
            @RequestParam(required = false) McpServerStatus status
    ) {
        return ResponseEntity.ok(mcpServerRegistryService.getServers(status));
    }

    @GetMapping("/{serverId}")
    public ResponseEntity<McpServerResponseDTO> getServer(@PathVariable String serverId) {
        return ResponseEntity.ok(mcpServerRegistryService.getServer(serverId));
    }

    @PutMapping("/{serverId}")
    public ResponseEntity<McpServerResponseDTO> updateServer(
            @PathVariable String serverId,
            @Valid @RequestBody McpServerRequestDTO request
    ) {
        return ResponseEntity.ok(mcpServerRegistryService.updateServer(serverId, request));
    }

    @PatchMapping("/{serverId}/status")
    public ResponseEntity<McpServerResponseDTO> updateStatus(
            @PathVariable String serverId,
            @Valid @RequestBody McpServerStatusUpdateDTO request
    ) {
        return ResponseEntity.ok(mcpServerRegistryService.updateStatus(serverId, request.status()));
    }

    @GetMapping("/{serverId}/tools")
    public Mono<ResponseEntity<McpSchema.ListToolsResult>> listTools(@PathVariable String serverId) {
        return mcpClientService.listTools(serverId)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{serverId}/tools/known")
    public ResponseEntity<List<McpToolResponseDTO>> getKnownTools(
            @PathVariable String serverId,
            @RequestParam(defaultValue = "false") boolean enabledOnly
    ) {
        return ResponseEntity.ok(mcpToolRegistryService.getKnownTools(serverId, enabledOnly));
    }

    @PostMapping("/{serverId}/tools/sync")
    public ResponseEntity<McpToolSyncResultDTO> syncTools(@PathVariable String serverId) {
        return ResponseEntity.ok(mcpToolRegistryService.syncTools(serverId));
    }

    @GetMapping("/{serverId}/executions")
    public ResponseEntity<List<McpToolExecutionResponseDTO>> getExecutions(
            @PathVariable String serverId,
            @RequestParam(required = false) String toolName
    ) {
        return ResponseEntity.ok(mcpToolExecutionService.getExecutions(serverId, toolName));
    }

    @PostMapping("/{serverId}/tools/{toolName}/call")
    public Mono<ResponseEntity<McpSchema.CallToolResult>> callTool(
            @PathVariable String serverId,
            @PathVariable String toolName,
            @RequestBody(required = false) McpToolCallRequestDTO request
    ) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        return mcpToolExecutionService.callTool(
                        serverId,
                        toolName,
                        arguments,
                        request == null ? null : request.maxAllowedTrustLevel()
                )
                .map(ResponseEntity::ok);
    }
}
