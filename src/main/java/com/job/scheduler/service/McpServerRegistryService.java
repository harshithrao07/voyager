package com.job.scheduler.service;

import com.job.scheduler.dto.McpServerRequestDTO;
import com.job.scheduler.dto.McpServerResponseDTO;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.repository.McpServerRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;

@Service
@RequiredArgsConstructor
public class McpServerRegistryService {
    private static final String MCP_SERVER_NOT_FOUND_MESSAGE = "MCP server does not exist";

    private final McpServerRepository mcpServerRepository;

    @Transactional
    public McpServerResponseDTO registerServer(McpServerRequestDTO request) {
        validateRequest(request);
        if (mcpServerRepository.existsByServerId(request.serverId())) {
            throw new IllegalStateException("MCP server already exists: " + request.serverId());
        }

        McpServer server = new McpServer();
        server.setServerId(request.serverId());
        applyRequest(server, request);

        return toResponse(mcpServerRepository.save(server));
    }

    public List<McpServerResponseDTO> getServers(McpServerStatus status) {
        List<McpServer> servers = status == null
                ? mcpServerRepository.findAllByOrderByCreatedAtDesc()
                : mcpServerRepository.findByStatusOrderByCreatedAtDesc(status);

        return servers.stream()
                .map(this::toResponse)
                .toList();
    }

    public McpServerResponseDTO getServer(String serverId) {
        return toResponse(findServer(serverId));
    }

    @Transactional
    public McpServerResponseDTO updateServer(String serverId, McpServerRequestDTO request) {
        validateRequest(request);
        if (!serverId.equals(request.serverId())) {
            throw new IllegalArgumentException("Path serverId must match request serverId");
        }

        McpServer server = findServer(serverId);
        applyRequest(server, request);
        return toResponse(mcpServerRepository.save(server));
    }

    @Transactional
    public McpServerResponseDTO updateStatus(String serverId, McpServerStatus status) {
        McpServer server = findServer(serverId);
        server.setStatus(status);
        return toResponse(mcpServerRepository.save(server));
    }

    private McpServer findServer(String serverId) {
        return mcpServerRepository.findByServerId(serverId)
                .orElseThrow(() -> new EntityNotFoundException(MCP_SERVER_NOT_FOUND_MESSAGE));
    }

    private void applyRequest(McpServer server, McpServerRequestDTO request) {
        server.setDisplayName(request.displayName());
        server.setBaseUrl(trimTrailingSlash(request.baseUrl()));
        server.setEndpoint(request.endpoint());
        server.setTransport(request.transport());
        server.setAuthType(request.authType());
        server.setAuthTokenRef(blankToNull(request.authTokenRef()));
        server.setTrustLevel(request.trustLevel() == null ? McpTrustLevel.UNTRUSTED : request.trustLevel());
        server.setStatus(request.status() == null ? McpServerStatus.DISABLED : request.status());
        server.setRequestTimeoutMs(request.requestTimeoutMs());
    }

    private void validateRequest(McpServerRequestDTO request) {
        URI baseUri = URI.create(request.baseUrl());
        if (baseUri.getScheme() == null || baseUri.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute URL");
        }
        if (request.authType() == McpAuthType.BEARER_TOKEN && blankToNull(request.authTokenRef()) == null) {
            throw new IllegalArgumentException("authTokenRef is required for BEARER_TOKEN auth");
        }
        if (request.authType() == McpAuthType.NONE && blankToNull(request.authTokenRef()) != null) {
            throw new IllegalArgumentException("authTokenRef is only valid for authenticated servers");
        }
    }

    private String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private McpServerResponseDTO toResponse(McpServer server) {
        return new McpServerResponseDTO(
                server.getId(),
                server.getServerId(),
                server.getDisplayName(),
                server.getBaseUrl(),
                server.getEndpoint(),
                server.getTransport(),
                server.getAuthType(),
                server.getAuthTokenRef(),
                server.getTrustLevel(),
                server.getStatus(),
                server.getRequestTimeoutMs(),
                server.getCreatedAt(),
                server.getUpdatedAt()
        );
    }
}
