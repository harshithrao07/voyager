package com.job.scheduler.service;

import com.job.scheduler.dto.PublicMcpEnvVarDTO;
import com.job.scheduler.dto.PublicMcpInstallOptionDTO;
import com.job.scheduler.dto.PublicMcpServerDTO;
import com.job.scheduler.dto.PublicMcpRegistryPageDTO;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.enums.PublicMcpSource;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Searches a "public MCP library" for servers that provide a capability the assistant
 * asked for. Two layers: a JSON catalog shipped in-repo (always available, works
 * offline/air-gapped) and, when enabled, a live external registry that layers on top.
 * Results are recommendations the user registers through the normal MCP server form —
 * this service never touches the local server table.
 */
@Service
public class PublicMcpRegistryService {

    private static final Logger log = LoggerFactory.getLogger(PublicMcpRegistryService.class);
    private static final int MAX_LIMIT = 50;
    private static final int MAX_BROWSE_PAGE_HOPS = 5;

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${scheduler.mcp.registry.bundled-catalog:classpath:mcp/public-catalog.json}")
    private String bundledCatalogLocation;

    @Value("${scheduler.mcp.registry.external.enabled:false}")
    private boolean externalEnabled;

    @Value("${scheduler.mcp.registry.external.url:https://registry.modelcontextprotocol.io}")
    private String externalUrl;

    @Value("${scheduler.mcp.registry.external.timeout-ms:4000}")
    private long externalTimeoutMs;

    private volatile List<PublicMcpServerDTO> bundledCatalog = List.of();

    public PublicMcpRegistryService(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @PostConstruct
    void loadBundledCatalog() {
        try (InputStream in = resourceLoader.getResource(bundledCatalogLocation).getInputStream()) {
            List<PublicMcpServerDTO> parsed = objectMapper.readValue(
                    in, objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, PublicMcpServerDTO.class));
            bundledCatalog = parsed.stream()
                    .map(PublicMcpRegistryService::normalizeBundled)
                    .toList();
            log.info("Loaded {} bundled MCP catalog entries from {}",
                    bundledCatalog.size(), bundledCatalogLocation);
        } catch (Exception e) {
            log.warn("Could not load bundled MCP catalog from {}: {}",
                    bundledCatalogLocation, e.getMessage());
            bundledCatalog = List.of();
        }
    }

    /**
     * Ranked candidates matching {@code query}. Bundled entries always participate;
     * external ones are folded in when enabled and reachable, and a failure there is
     * swallowed so discovery degrades to the bundled catalog rather than erroring.
     */
    public List<PublicMcpServerDTO> search(String query, int limit) {
        int cappedLimit = limit <= 0 ? 10 : Math.min(limit, MAX_LIMIT);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        // Dedupe by name, preferring bundled (vetted) over external for the same server.
        Map<String, Scored> byName = new LinkedHashMap<>();
        for (PublicMcpServerDTO server : bundledCatalog) {
            double score = score(server, normalizedQuery);
            if (score > 0 || normalizedQuery.isEmpty()) {
                byName.put(dedupeKey(server), new Scored(server, score));
            }
        }
        if (externalEnabled) {
            for (PublicMcpServerDTO server : fetchExternal(normalizedQuery, cappedLimit)) {
                String key = dedupeKey(server);
                if (byName.containsKey(key)) {
                    continue; // bundled wins
                }
                double score = score(server, normalizedQuery);
                if (score > 0 || normalizedQuery.isEmpty()) {
                    byName.put(key, new Scored(server, score));
                }
            }
        }

        return byName.values().stream()
                .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed()
                        .thenComparing(s -> s.server.name(), Comparator.nullsLast(String::compareToIgnoreCase)))
                .limit(cappedLimit)
                .map(s -> s.server)
                .toList();
    }

    /**
     * Cursor-based browsing for the store UI. The first page includes matching bundled
     * recommendations; subsequent pages follow the official registry's opaque cursor.
     */
    public PublicMcpRegistryPageDTO browse(String query, int limit, String cursor) {
        int cappedLimit = limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        if (!externalEnabled) {
            List<PublicMcpServerDTO> offline = cursor == null || cursor.isBlank()
                    ? search(normalizedQuery, cappedLimit)
                    : List.of();
            return new PublicMcpRegistryPageDTO(offline, null);
        }

        Map<String, PublicMcpServerDTO> externalByName = new LinkedHashMap<>();
        String pageCursor = cursor;
        String nextCursor = cursor;
        for (int hop = 0; hop < MAX_BROWSE_PAGE_HOPS; hop++) {
            ExternalPage page = fetchExternalPage(normalizedQuery, cappedLimit, pageCursor);
            page.servers().forEach(server ->
                    externalByName.putIfAbsent(dedupeKey(server), server));
            nextCursor = page.nextCursor();
            if (nextCursor == null
                    || nextCursor.isBlank()
                    || Objects.equals(nextCursor, pageCursor)
                    || externalByName.size() >= cappedLimit) {
                break;
            }
            pageCursor = nextCursor;
        }

        Map<String, PublicMcpServerDTO> merged = new LinkedHashMap<>();
        if (cursor == null || cursor.isBlank()) {
            bundledCatalog.stream()
                    .filter(server -> normalizedQuery.isEmpty() || score(server, normalizedQuery) > 0)
                    .forEach(server -> merged.put(dedupeKey(server), server));
        }
        externalByName.values().forEach(server ->
                merged.putIfAbsent(dedupeKey(server), server));
        return new PublicMcpRegistryPageDTO(List.copyOf(merged.values()), nextCursor);
    }

    private record Scored(PublicMcpServerDTO server, double score) {
    }

    private static String dedupeKey(PublicMcpServerDTO server) {
        String name = server.name() == null ? "" : server.name();
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Substring match over name + description + install identifiers, weighted so a name
     * hit outranks a description hit. An empty query matches everything (score 0) so the
     * caller can list the catalog.
     */
    private static double score(PublicMcpServerDTO server, String query) {
        if (query.isEmpty()) {
            return 0;
        }
        double score = 0;
        String name = lower(server.name());
        String description = lower(server.description());
        for (String term : query.split("\\s+")) {
            if (term.isBlank()) {
                continue;
            }
            if (name.contains(term)) {
                score += name.equals(term) ? 5 : 3;
            }
            if (description.contains(term)) {
                score += 1;
            }
            if (installsContain(server, term)) {
                score += 1;
            }
        }
        return score;
    }

    private static boolean installsContain(PublicMcpServerDTO server, String term) {
        if (server.installs() == null) {
            return false;
        }
        for (PublicMcpInstallOptionDTO install : server.installs()) {
            if (install.command() != null && lower(install.command()).contains(term)) {
                return true;
            }
            if (install.args() != null
                    && install.args().stream().anyMatch(a -> lower(a).contains(term))) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static PublicMcpServerDTO normalizeBundled(PublicMcpServerDTO raw) {
        return new PublicMcpServerDTO(
                raw.sourceId(),
                raw.name(),
                raw.description(),
                raw.version(),
                raw.repositoryUrl(),
                PublicMcpSource.BUNDLED,
                raw.installs() == null ? List.of() : raw.installs(),
                raw.suggestedTrustLevel() == null ? McpTrustLevel.UNTRUSTED : raw.suggestedTrustLevel());
    }

    // ---- External registry (official MCP registry schema) ----

    private List<PublicMcpServerDTO> fetchExternal(String query, int limit) {
        return fetchExternalPage(query, limit, null).servers();
    }

    private ExternalPage fetchExternalPage(String query, int limit, String cursor) {
        try {
            String base = externalUrl.endsWith("/")
                    ? externalUrl.substring(0, externalUrl.length() - 1)
                    : externalUrl;
            StringBuilder uri = new StringBuilder(base).append("/v0.1/servers?limit=").append(limit);
            if (!query.isBlank()) {
                uri.append("&search=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            }
            if (cursor != null && !cursor.isBlank()) {
                uri.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(uri.toString()))
                    .timeout(Duration.ofMillis(externalTimeoutMs))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("External MCP registry returned HTTP {} for query '{}'", response.statusCode(), query);
                return ExternalPage.empty();
            }
            return parseExternalPage(response.body());
        } catch (Exception e) {
            log.warn("External MCP registry lookup failed ({}); using bundled catalog only", e.toString());
            return ExternalPage.empty();
        }
    }

    List<PublicMcpServerDTO> parseExternal(String body) {
        return parseExternalPage(body).servers();
    }

    ExternalPage parseExternalPage(String body) {
        List<PublicMcpServerDTO> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode servers = root.path("servers");
            if (!servers.isArray()) {
                return ExternalPage.empty();
            }
            for (JsonNode entry : servers) {
                // Newer registry responses nest the server document under "server".
                JsonNode server = entry.has("server") ? entry.path("server") : entry;
                PublicMcpServerDTO mapped = mapExternalServer(server);
                if (mapped != null && !mapped.installs().isEmpty()) {
                    results.add(mapped);
                }
            }
            JsonNode metadata = root.path("metadata");
            String nextCursor = firstText(metadata, "nextCursor", "next_cursor");
            return new ExternalPage(List.copyOf(results), nextCursor);
        } catch (Exception e) {
            log.warn("Could not parse external MCP registry response: {}", e.getMessage());
        }
        return ExternalPage.empty();
    }

    record ExternalPage(List<PublicMcpServerDTO> servers, String nextCursor) {
        static ExternalPage empty() {
            return new ExternalPage(List.of(), null);
        }
    }

    private PublicMcpServerDTO mapExternalServer(JsonNode server) {
        String name = text(server, "name");
        if (name == null || name.isBlank()) {
            return null;
        }
        List<PublicMcpInstallOptionDTO> installs = new ArrayList<>();
        for (JsonNode pkg : server.path("packages")) {
            PublicMcpInstallOptionDTO option = mapPackage(pkg);
            if (option != null) {
                installs.add(option);
            }
        }
        for (JsonNode remote : server.path("remotes")) {
            PublicMcpInstallOptionDTO option = mapRemote(remote);
            if (option != null) {
                installs.add(option);
            }
        }
        String repository = text(server.path("repository"), "url");
        return new PublicMcpServerDTO(
                name,
                displayNameFrom(name),
                text(server, "description"),
                text(server, "version"),
                repository,
                PublicMcpSource.EXTERNAL,
                installs,
                McpTrustLevel.UNTRUSTED);
    }

    /** npm -> npx, pypi -> uvx, oci -> docker run; identifier + declared args + env. */
    private PublicMcpInstallOptionDTO mapPackage(JsonNode pkg) {
        String registryType = firstText(pkg, "registryType", "registry_name", "registry_type");
        String identifier = firstText(pkg, "identifier", "name");
        if (registryType == null || identifier == null) {
            return null;
        }
        String version = text(pkg, "version");
        String runtimeHint = text(pkg, "runtimeHint");
        List<String> runtimeArgs = argValues(pkg.path("runtimeArguments"));
        List<String> packageArgs = argValues(pkg.path("packageArguments"));
        List<PublicMcpEnvVarDTO> env = envVars(pkg.path("environmentVariables"));

        String type = registryType.toLowerCase(Locale.ROOT);
        List<String> args = new ArrayList<>();
        String command;
        String label;
        switch (type) {
            case "npm" -> {
                command = runtimeHint != null ? runtimeHint : "npx";
                args.add("-y");
                args.add(version == null || version.isBlank() ? identifier : identifier + "@" + version);
                label = "npm (" + command + ")";
            }
            case "pypi" -> {
                command = runtimeHint != null ? runtimeHint : "uvx";
                args.add(identifier);
                label = "PyPI (" + command + ")";
            }
            case "oci", "docker" -> {
                command = runtimeHint != null ? runtimeHint : "docker";
                args.add("run");
                args.add("-i");
                args.add("--rm");
                for (PublicMcpEnvVarDTO var : env) {
                    args.add("-e");
                    args.add(var.name());
                }
                args.add(version == null || version.isBlank() ? identifier : identifier + ":" + version);
                label = "Docker";
            }
            default -> {
                // Unknown packaging: surface the runtime hint if any, else skip.
                if (runtimeHint == null) {
                    return null;
                }
                command = runtimeHint;
                args.add(identifier);
                label = registryType;
            }
        }
        args.addAll(runtimeArgs);
        args.addAll(packageArgs);
        return new PublicMcpInstallOptionDTO(
                label, McpTransport.STDIO, command, args, null, null, env);
    }

    private PublicMcpInstallOptionDTO mapRemote(JsonNode remote) {
        String url = text(remote, "url");
        if (url == null || url.isBlank()) {
            return null;
        }
        String type = firstText(remote, "type", "transport");
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            StringBuilder base = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() != -1) {
                base.append(':').append(uri.getPort());
            }
            String endpoint = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/mcp" : uri.getRawPath();
            String label = "Remote" + (type == null ? "" : " (" + type + ")");
            return new PublicMcpInstallOptionDTO(
                    label, McpTransport.HTTP, null, null, base.toString(), endpoint, List.of());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> argValues(JsonNode argsNode) {
        List<String> values = new ArrayList<>();
        if (!argsNode.isArray()) {
            return values;
        }
        for (JsonNode arg : argsNode) {
            // Positional args carry "value"; named args carry "name" (a flag like --port).
            String value = firstText(arg, "value", "name");
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
            String defaultValue = text(arg, "default");
            if (arg.has("name") && defaultValue != null && !defaultValue.isBlank()) {
                values.add(defaultValue);
            }
        }
        return values;
    }

    private static List<PublicMcpEnvVarDTO> envVars(JsonNode envNode) {
        List<PublicMcpEnvVarDTO> vars = new ArrayList<>();
        if (!envNode.isArray()) {
            return vars;
        }
        for (JsonNode var : envNode) {
            String name = text(var, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            vars.add(new PublicMcpEnvVarDTO(
                    name,
                    text(var, "description"),
                    var.path("isSecret").asBoolean(false) || var.path("secret").asBoolean(false),
                    var.path("isRequired").asBoolean(false) || var.path("required").asBoolean(false),
                    text(var, "default")));
        }
        return vars;
    }

    private static String displayNameFrom(String registryName) {
        // "io.github.owner/server-name" -> "server-name"
        String tail = registryName.contains("/")
                ? registryName.substring(registryName.lastIndexOf('/') + 1)
                : registryName;
        return tail.isBlank() ? registryName : tail;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        return Arrays.stream(fields)
                .map(f -> text(node, f))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
