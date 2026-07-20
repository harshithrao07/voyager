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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class McpServerRegistryService {
    private static final String MCP_SERVER_NOT_FOUND_MESSAGE = "MCP server does not exist";
    private static final Pattern HTTP_HEADER_NAME = Pattern.compile(
            "[!#$%&'*+.^_`|~0-9A-Za-z-]+"
    );
    private static final Set<String> RESTRICTED_HTTP_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "upgrade"
    );

    private final McpServerRepository mcpServerRepository;
    private final SecretCipher secretCipher;

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
        server.setSecretEnv(mergeEncryptedValues(server.getSecretEnv(), request.secretEnv()));
        server.setAuthEnvVar(blankToNull(request.authEnvVar()));
        server.setTransport(request.transport());
        server.setAuthType(request.authType());
        applyAuthToken(server, request);
        applySecretHeaders(server, request);
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
        // Per-type extra config.
        if (request.authType() == McpAuthType.API_KEY
                && blankToNull(request.authHeaderName()) == null) {
            throw new IllegalArgumentException("authHeaderName is required for API_KEY auth");
        }
        if (request.authType() == McpAuthType.BASIC
                && blankToNull(request.authUsername()) == null) {
            throw new IllegalArgumentException("authUsername is required for BASIC auth");
        }
        if (request.authType() == McpAuthType.CUSTOM_HEADERS) {
            validateSecretHeaders(request.secretHeaders());
        }
    }

    private void validateSecretHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one header is required for CUSTOM_HEADERS auth");
        }
        Set<String> normalizedNames = new LinkedHashSet<>();
        headers.forEach((name, value) -> {
            if (name == null || !HTTP_HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "Invalid custom authentication header name: " + name);
            }
            String normalizedName = name.toLowerCase(Locale.ROOT);
            if (RESTRICTED_HTTP_HEADERS.contains(normalizedName)) {
                throw new IllegalArgumentException(
                        "Header is managed by the HTTP transport and cannot be configured: " + name);
            }
            if (!normalizedNames.add(normalizedName)) {
                throw new IllegalArgumentException(
                        "Duplicate custom authentication header name: " + name);
            }
            if (value != null && (value.contains("\r") || value.contains("\n"))) {
                throw new IllegalArgumentException(
                        "Custom authentication header values cannot contain line breaks");
            }
        });
    }

    /**
     * Authenticated types require a token: a provided plaintext token is encrypted;
     * a blank token keeps the existing encrypted value (so edits need not re-enter
     * it), and is rejected only when none is stored yet. NONE clears the token.
     */
    private void applyAuthToken(McpServer server, McpServerRequestDTO request) {
        // NONE and CUSTOM_HEADERS carry no single token.
        if (request.authType() == McpAuthType.NONE
                || request.authType() == McpAuthType.CUSTOM_HEADERS) {
            server.setAuthTokenEncrypted(null);
            return;
        }
        String plaintext = blankToNull(request.authToken());
        if (plaintext != null) {
            server.setAuthTokenEncrypted(secretCipher.encrypt(plaintext));
        } else if (server.getAuthTokenEncrypted() == null) {
            throw new IllegalArgumentException(
                    "authToken is required for " + request.authType() + " auth");
        }
    }

    /**
     * CUSTOM_HEADERS stores an encrypted header map (at least one required); other
     * auth types clear it. Merge semantics match secret env (blank value keeps the
     * existing encrypted value; absent keys are dropped).
     */
    private void applySecretHeaders(McpServer server, McpServerRequestDTO request) {
        if (request.authType() != McpAuthType.CUSTOM_HEADERS) {
            server.setSecretHeaders(new LinkedHashMap<>());
            return;
        }
        Map<String, String> merged = mergeEncryptedValues(
                server.getSecretHeaders(), request.secretHeaders());
        if (merged.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one header is required for CUSTOM_HEADERS auth");
        }
        server.setSecretHeaders(merged);
    }

    /**
     * Builds a stored encrypted map: a provided plaintext value is encrypted; a
     * blank value keeps the existing encrypted value (unchanged) for that key; keys
     * absent from the request are dropped.
     */
    private Map<String, String> mergeEncryptedValues(
            Map<String, String> existing,
            Map<String, String> requested
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        if (requested == null) {
            return result;
        }
        Map<String, String> current = existing == null ? Map.of() : existing;
        requested.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                if (current.containsKey(name)) {
                    result.put(name, current.get(name));
                }
            } else {
                result.put(name, secretCipher.encrypt(value));
            }
        });
        return result;
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
                server.getSecretEnv() == null ? Set.of() : Set.copyOf(server.getSecretEnv().keySet()),
                server.getSecretHeaders() == null ? Set.of() : Set.copyOf(server.getSecretHeaders().keySet()),
                server.getAuthEnvVar(),
                server.getTransport(),
                server.getAuthType(),
                server.getAuthTokenEncrypted() != null,
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
