package com.job.scheduler.service;

import com.job.scheduler.dto.McpServerRequestDTO;
import com.job.scheduler.dto.McpServerResponseDTO;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpAuthType;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.repository.McpServerRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        server.setBaseUrl(trimTrailingSlash(blankToNull(request.baseUrl())));
        server.setEndpoint(blankToNull(request.endpoint()));
        server.setCommand(blankToNull(request.command()));
        server.setArgs(request.args() == null ? new ArrayList<>() : new ArrayList<>(request.args()));
        server.setEnv(request.env() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.env()));
        server.setAuthEnvVar(blankToNull(request.authEnvVar()));
        server.setTransport(request.transport());
        server.setAuthType(request.authType());
        server.setAuthTokenRef(blankToNull(request.authTokenRef()));
        server.setAuthHeaderName(blankToNull(request.authHeaderName()));
        server.setAuthUsername(blankToNull(request.authUsername()));
        server.setTrustLevel(request.trustLevel() == null ? McpTrustLevel.UNTRUSTED : request.trustLevel());
        server.setStatus(request.status() == null ? McpServerStatus.DISABLED : request.status());
        server.setRequestTimeoutMs(request.requestTimeoutMs());
    }

    private void validateRequest(McpServerRequestDTO request) {
        if (request.transport() == McpTransport.HTTP) {
            String baseUrl = blankToNull(request.baseUrl());
            if (baseUrl == null) {
                throw new IllegalArgumentException("baseUrl is required for HTTP transport");
            }
            URI baseUri = URI.create(baseUrl);
            if (baseUri.getScheme() == null || baseUri.getHost() == null) {
                throw new IllegalArgumentException("baseUrl must be an absolute URL");
            }
            if (blankToNull(request.endpoint()) == null) {
                throw new IllegalArgumentException("endpoint is required for HTTP transport");
            }
        } else if (request.transport() == McpTransport.STDIO) {
            if (blankToNull(request.command()) == null) {
                throw new IllegalArgumentException("command is required for STDIO transport");
            }
            if (request.authType() != McpAuthType.NONE
                    && request.authType() != McpAuthType.BEARER_TOKEN) {
                throw new IllegalArgumentException(
                        "STDIO transport supports only NONE or BEARER_TOKEN auth");
            }
            if (request.authType() == McpAuthType.BEARER_TOKEN
                    && blankToNull(request.authEnvVar()) == null) {
                throw new IllegalArgumentException(
                        "authEnvVar is required for STDIO BEARER_TOKEN auth");
            }
        }
        // A secret reference is required for any authenticated type, invalid for NONE.
        if (request.authType() == McpAuthType.NONE) {
            if (blankToNull(request.authTokenRef()) != null) {
                throw new IllegalArgumentException("authTokenRef is only valid for authenticated servers");
            }
        } else if (blankToNull(request.authTokenRef()) == null) {
            throw new IllegalArgumentException(
                    "authTokenRef is required for " + request.authType() + " auth");
        }
        // Per-type extra config.
        if (request.authType() == McpAuthType.API_KEY
                && blankToNull(request.authHeaderName()) == null) {
            throw new IllegalArgumentException("authHeaderName is required for API_KEY auth");
        }
        if (request.authType() == McpAuthType.BASIC
                && blankToNull(request.authUsername()) == null) {
            throw new IllegalArgumentException("authUsername is required for BASIC auth");
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
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
                server.getCommand(),
                server.getArgs(),
                server.getEnv(),
                server.getAuthEnvVar(),
                server.getTransport(),
                server.getAuthType(),
                server.getAuthTokenRef(),
                server.getAuthHeaderName(),
                server.getAuthUsername(),
                server.getTrustLevel(),
                server.getStatus(),
                server.getRequestTimeoutMs(),
                server.getCreatedAt(),
                server.getUpdatedAt()
        );
    }
}
