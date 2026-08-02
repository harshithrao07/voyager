package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionLanguageDTO;
import com.job.scheduler.dto.WorkflowAiMcpRequirementDTO;
import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.enums.ResourceEmbeddingType;
import com.job.scheduler.repository.FunctionDefinitionRepository;
import com.job.scheduler.repository.McpToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds the live Task-resource catalog supplied to workflow-generation models. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAiResourceCatalogService {
    private static final Set<String> MATCH_STOP_WORDS = Set.of(
            "a", "an", "and", "for", "from", "in", "of", "on", "or", "the", "to", "with",
            "access", "api", "capability", "current", "data", "information", "result",
            "results", "service", "tool", "create", "exact", "registered", "scheduler",
            "scheduled", "state", "task", "unscheduled", "use", "using", "voyager", "workflow",
            "catalog", "complete", "example", "function", "generated", "has", "live",
            "match", "matches", "matching", "mcp", "missing", "not", "propose", "proposal",
            "resource", "that", "when", "your"
    );
    private final FunctionDefinitionRepository functionRepository;
    private final McpToolRepository mcpToolRepository;
    private final FunctionRuntimePolicy functionRuntimePolicy;
    private final ObjectMapper objectMapper;
    private final WorkflowAiEmbeddingService embeddingService;

    private static final String CATALOG_TEMPLATE = """
            Match every requested action against the descriptions below. A matching entry is
            mandatory: create a Task with the exact URI shown, never a Pass state, shortened
            name, alias, or invented resource. A WRITE or DESTRUCTIVE MCP resource must keep
            the shown trust query parameter. Each MCP args object is the Task Arguments shape:
            use those exact top-level keys with JSONata values and do not wrap them in payload
            unless payload is itself listed. MCP business results are nested under
            $states.result.structuredContent.

            SYSTEM RESOURCES:
            - voyager://system/webhook args: {url:string, method:string optional default POST,
              headers:object<string,string> optional, body:any optional}. Supported methods are
              GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS. Put request headers in the
              headers object; do not invent top-level header fields.
            - voyager://system/send-email args: {to:string, subject:string, body:string}

            FUNCTIONS:
            %s

            MCP TOOLS:
            %s
            """;

    /** Full-detail catalog (every resource). Used when no turn intent is available. */
    public String buildCatalog() {
        return buildCatalog(null);
    }

    /**
     * Catalog for a turn, tiered by relevance to {@code intent}. When embedding-based retrieval is
     * active (enabled, an embedding model is registered, and the catalog is large enough), only the
     * top-k resources nearest the intent carry full detail — MCP argument schemas and descriptions;
     * the rest render as a one-line index so the model still sees the complete menu. Below the size
     * threshold, on a blank intent, or if retrieval yields nothing, every resource keeps full detail
     * (identical to {@link #buildCatalog()}).
     *
     * <p>Note: tiering makes the catalog vary per turn, so it no longer sits in the byte-identical
     * cacheable prompt prefix. That trade is deliberate — retrieval only engages for catalogs large
     * enough that the token saving outweighs the lost KV-cache reuse.
     */
    public String buildCatalog(String intent) {
        List<FunctionDefinition> functions = enabledFunctions();
        List<McpTool> tools = enabledMcpTools();

        Set<String> detailedFunctionIds = null; // null => every resource is detailed
        Set<String> detailedMcpIds = null;
        if (intent != null && !intent.isBlank()
                && embeddingService.retrievalActive(functions.size() + tools.size())) {
            Set<String> relevantFunctions = embeddingService
                    .selectRelevantResourceIds(ResourceEmbeddingType.FUNCTION, intent);
            Set<String> relevantMcp = embeddingService
                    .selectRelevantResourceIds(ResourceEmbeddingType.MCP_TOOL, intent);
            // An empty result means retrieval failed or matched nothing; keep that type fully
            // detailed rather than silently dropping resources the model might need.
            detailedFunctionIds = relevantFunctions.isEmpty() ? null : relevantFunctions;
            detailedMcpIds = relevantMcp.isEmpty() ? null : relevantMcp;
        }

        return CATALOG_TEMPLATE.formatted(
                buildFunctionsDocumentation(functions, detailedFunctionIds),
                buildMcpToolsDocumentation(tools, detailedMcpIds)
        );
    }

    /** Number of live resources that would otherwise be sent in the prompt catalog. */
    public int resourceCount() {
        return 2 + enabledFunctions().size() + enabledMcpTools().size();
    }

    /** Approximate tokens the complete prompt-catalog block would consume for this intent. */
    public int estimatePromptCatalogTokens(String intent) {
        String catalog = "Available Voyager Task resources (current registry):\n"
                + buildCatalog(intent);
        return 4 + (catalog.length() + 3) / 4;
    }

    /**
     * Returns a small structured result set for model tool-calling. Unlike {@link #buildCatalog},
     * resources outside the result set consume no prompt tokens. Embedding retrieval supplies the
     * candidate boost when available; lexical scoring is the precision gate. Embeddings may reorder
     * lexically related entries, but cannot turn an unrelated nearest neighbour into a match. That
     * keeps an empty result meaningful evidence that Voyager lacks the requested capability.
     */
    public List<CatalogSearchResult> searchCatalog(String query, int requestedLimit) {
        String intent = query == null ? "" : query.trim();
        int limit = Math.max(1, Math.min(requestedLimit, 8));
        List<FunctionDefinition> functions = enabledFunctions();
        List<McpTool> tools = enabledMcpTools();
        Set<String> relevantFunctions = embeddingService.retrievalActive(functions.size() + tools.size())
                ? embeddingService.selectRelevantResourceIds(ResourceEmbeddingType.FUNCTION, intent)
                : Set.of();
        Set<String> relevantMcp = embeddingService.retrievalActive(functions.size() + tools.size())
                ? embeddingService.selectRelevantResourceIds(ResourceEmbeddingType.MCP_TOOL, intent)
                : Set.of();

        List<ScoredCatalogResult> candidates = new ArrayList<>();
        candidates.add(scored(new CatalogSearchResult(
                "voyager://system/webhook", "SYSTEM", "Call an HTTP webhook",
                "{url:string, method:string optional default POST, headers:object<string,string> optional, body:any optional}",
                "READ"), intent, false));
        candidates.add(scored(new CatalogSearchResult(
                "voyager://system/send-email", "SYSTEM", "Send an email",
                "{to:string, subject:string, body:string}", "WRITE"), intent, false));
        for (FunctionDefinition function : functions) {
            CatalogSearchResult result = new CatalogSearchResult(
                    "voyager://function/" + function.getName() + "@v" + function.getActiveVersion(),
                    "FUNCTION", function.getDescription(), "{}", "LOCAL");
            candidates.add(scored(result, intent, function.getId() != null
                    && relevantFunctions.contains(function.getId().toString())));
        }
        for (McpTool tool : tools) {
            McpTrustLevel trust = tool.getMcpServer().getTrustLevel() == null
                    ? McpTrustLevel.UNTRUSTED : tool.getMcpServer().getTrustLevel();
            CatalogSearchResult result = new CatalogSearchResult(
                    mcpResourceUri(tool), "MCP_TOOL", tool.getDescription(),
                    compactSchema(tool.getInputSchema()), trust.name());
            candidates.add(scored(result, intent, tool.getId() != null
                    && relevantMcp.contains(tool.getId().toString())));
        }
        int strongestMatch = candidates.stream()
                .mapToInt(ScoredCatalogResult::matchScore)
                .max()
                .orElse(0);
        return candidates.stream()
                .filter(candidate -> strongestMatch > 0
                        && candidate.matchScore() == strongestMatch)
                .sorted(Comparator.comparingInt(ScoredCatalogResult::score).reversed()
                        .thenComparing(candidate -> candidate.result().uri()))
                .limit(limit)
                .map(ScoredCatalogResult::result)
                .toList();
    }

    private ScoredCatalogResult scored(CatalogSearchResult result, String query, boolean embeddingMatch) {
        Set<String> queryTokens = tokens(query);
        Set<String> identifierTokens = tokens(result.uri());
        Set<String> resourceTokens = tokens(String.join(" ",
                result.uri(), safe(result.description())));
        int lexical = (int) queryTokens.stream().filter(resourceTokens::contains).count();
        int fileAdjustment = fileIntentAdjustment(query, result);
        boolean exactIdentifierIntent = queryTokens.size() == 1
                && queryTokens.stream().anyMatch(identifierTokens::contains);
        boolean sufficientEvidence = lexical >= 2
                || exactIdentifierIntent
                || (lexical >= 1 && fileAdjustment != 0);
        int matchScore = sufficientEvidence ? lexical * 10 + fileAdjustment : 0;
        return new ScoredCatalogResult(
                result,
                matchScore + (embeddingMatch ? 1 : 0),
                matchScore
        );
    }

    private int fileIntentAdjustment(String query, CatalogSearchResult result) {
        String intent = normalize(query);
        Set<String> intentTokens = tokens(intent);
        boolean fileActionIntent = intentTokens.stream().anyMatch(Set.of(
                "find", "list", "load", "open", "read", "save", "search", "write"
        )::contains);
        boolean explicitFileIntent = intentTokens.stream().anyMatch(Set.of(
                "file", "filesystem", "readme"
        )::contains);
        boolean textFormatIntent = intentTokens.stream().anyMatch(Set.of(
                "readme", "markdown", "text", "txt", "json", "yaml", "yml", "csv",
                "xml", "html", "log", "java", "javascript", "typescript", "python",
                "properties"
        )::contains);
        boolean textFileIntent = explicitFileIntent || (fileActionIntent && textFormatIntent);
        if (!textFileIntent) {
            return 0;
        }
        String uri = result.uri().toLowerCase(Locale.ROOT);
        String description = safe(result.description()).toLowerCase(Locale.ROOT);
        if (uri.contains("read_text_file")) {
            return 50;
        }
        if (uri.contains("read_media_file") || description.contains("deprecated")) {
            return -50;
        }
        return 0;
    }

    private String compactSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(schema));
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private List<FunctionDefinition> enabledFunctions() {
        return functionRepository.findByStatusNotOrderByUpdatedAtDesc(FunctionStatus.ARCHIVED)
                .stream()
                .filter(function -> function.getStatus() == FunctionStatus.ENABLED
                        && function.getActiveVersion() != null)
                .toList();
    }

    private List<McpTool> enabledMcpTools() {
        return mcpToolRepository.findByEnabledTrue().stream()
                .filter(tool -> tool.getMcpServer() != null
                        && tool.getMcpServer().getStatus() == McpServerStatus.ENABLED)
                .toList();
    }

    /**
     * Function-authoring context is deliberately separate from the normal Task catalog. It is
     * supplied to the workflow model only after that model has chosen to propose a function.
     */
    public String buildFunctionCreationContext() {
        FunctionLanguageDTO language = functionRuntimePolicy.aiDefaultLanguage();
        return language == null
                ? "AI DEFAULT FUNCTION LANGUAGE: unavailable. Do not propose a function."
                : """
                  AI DEFAULT FUNCTION LANGUAGE (always use this exact languageId and language):
                  - %d — %s
                  """.formatted(language.id(), language.name()).trim();
    }

    String buildLanguagesDocumentation() {
        try {
            List<FunctionLanguageDTO> languages = functionRuntimePolicy.supportedSelectableLanguages();
            StringBuilder documentation = new StringBuilder();
            for (FunctionLanguageDTO language : languages) {
                documentation.append("- ")
                        .append(language.id())
                        .append(" — ")
                        .append(language.name())
                        .append('\n');
            }
            return documentation.isEmpty() ? "Language list unavailable." : documentation.toString().trim();
        } catch (RuntimeException exception) {
            // The runtime (Judge0) may be unreachable; the catalog must still build so existing
            // resources can be wired. Proposing new functions simply lacks the language hint.
            log.warn("Could not load supported function languages for the AI catalog", exception);
            return "Language list unavailable.";
        }
    }

    /** Full-detail function documentation for every enabled function. */
    String buildFunctionsDocumentation() {
        return buildFunctionsDocumentation(enabledFunctions(), null);
    }

    /** Full-detail MCP tool documentation for every enabled tool. */
    String buildMcpToolsDocumentation() {
        return buildMcpToolsDocumentation(enabledMcpTools(), null);
    }

    /**
     * @param detailedIds resource ids that render with full detail; {@code null} means every
     *                    function is detailed. Functions are cheap one-liners, so the index tier
     *                    only drops the description.
     */
    String buildFunctionsDocumentation(
            List<FunctionDefinition> functions,
            Set<String> detailedIds
    ) {
        StringBuilder documentation = new StringBuilder();
        for (FunctionDefinition function : functions) {
            documentation.append("- voyager://function/")
                    .append(function.getName())
                    .append("@v")
                    .append(function.getActiveVersion());
            if (isDetailed(detailedIds, function.getId())) {
                appendDescription(documentation, function.getDescription());
            }
            documentation.append('\n');
        }
        return documentation.isEmpty() ? "None registered." : documentation.toString().trim();
    }

    /**
     * @param detailedIds resource ids that render with full detail (argument schema + description);
     *                    {@code null} means every tool is detailed. Index-tier tools keep only the
     *                    URI and trust marker so the model still sees the tool exists, without the
     *                    (often large) argument schema.
     */
    String buildMcpToolsDocumentation(
            List<McpTool> tools,
            Set<String> detailedIds
    ) {
        StringBuilder documentation = new StringBuilder();
        for (McpTool tool : tools) {
            McpServer server = tool.getMcpServer();
            McpTrustLevel trustLevel = server.getTrustLevel() == null
                    ? McpTrustLevel.UNTRUSTED
                    : server.getTrustLevel();
            documentation.append("- voyager://mcp/")
                    .append(server.getServerId())
                    .append('/')
                    .append(tool.getToolName());
            if (trustLevel == McpTrustLevel.WRITE
                    || trustLevel == McpTrustLevel.DESTRUCTIVE) {
                documentation.append("?trust=").append(trustLevel);
            }
            documentation.append(" [trust: ").append(trustLevel).append(']');
            if (isDetailed(detailedIds, tool.getId())) {
                String arguments = flattenJsonSchema(tool.getInputSchema());
                if (!"{}".equals(arguments)) {
                    documentation.append(" args: ").append(arguments);
                }
                appendDescription(documentation, tool.getDescription());
            }
            documentation.append('\n');
        }
        return documentation.isEmpty() ? "None registered." : documentation.toString().trim();
    }

    private boolean isDetailed(Set<String> detailedIds, UUID resourceId) {
        return detailedIds == null || detailedIds.contains(resourceId.toString());
    }

    /**
     * Resolves capability-only MCP requirements against the current discovered catalog. Models
     * often suggest a provider/server name (for example {@code tavily-web-search}) rather than the
     * exact advertised tool name ({@code tavily_search}), so matching considers the server id,
     * tool name/title, and description. The exact URI is returned for the follow-up model prompt.
     */
    public List<McpRequirementMatch> findMcpRequirementMatches(
            List<WorkflowAiMcpRequirementDTO> requirements
    ) {
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        List<McpTool> enabledTools = mcpToolRepository.findByEnabledTrue().stream()
                .filter(tool -> tool.getMcpServer() != null)
                .filter(tool -> tool.getMcpServer().getStatus() == McpServerStatus.ENABLED)
                .toList();

        return requirements.stream()
                .filter(requirement -> requirement != null
                        && requirement.capability() != null
                        && !requirement.capability().isBlank())
                .map(requirement -> bestMatch(requirement, enabledTools))
                .filter(match -> match != null)
                .toList();
    }

    private McpRequirementMatch bestMatch(
            WorkflowAiMcpRequirementDTO requirement,
            List<McpTool> enabledTools
    ) {
        String suggestion = normalize(requirement.suggestedToolName());
        Set<String> requirementTokens = tokens(
                String.join(" ", safe(requirement.capability()), safe(requirement.reason()))
        );
        McpTool best = null;
        int bestScore = 0;
        for (McpTool tool : enabledTools) {
            McpServer server = tool.getMcpServer();
            String resourceUri = mcpResourceUri(tool);
            if (requirement.suggestedToolName() != null
                    && requirement.suggestedToolName().trim().equalsIgnoreCase(resourceUri)) {
                return new McpRequirementMatch(requirement.capability(), resourceUri);
            }
            String serverId = normalize(server.getServerId());
            String toolName = normalize(tool.getToolName());
            String title = normalize(tool.getTitle());
            boolean suggestionMatches = !suggestion.isBlank()
                    && (suggestion.equals(serverId)
                    || suggestion.equals(toolName)
                    || suggestion.equals(title)
                    || serverId.contains(suggestion)
                    || suggestion.contains(serverId)
                    || toolName.contains(suggestion)
                    || suggestion.contains(toolName));

            Set<String> toolTokens = tokens(String.join(" ",
                    safe(server.getServerId()),
                    safe(tool.getToolName()),
                    safe(tool.getTitle()),
                    safe(tool.getDescription())
            ));
            int overlap = (int) requirementTokens.stream().filter(toolTokens::contains).count();
            int score = (suggestionMatches ? 100 : 0) + overlap;
            // With no provider/name match, require two meaningful shared words (such as
            // "search" + "web") to avoid treating a generic tool description as sufficient.
            if (score > bestScore && (suggestionMatches || overlap >= 2)) {
                best = tool;
                bestScore = score;
            }
        }
        return best == null
                ? null
                : new McpRequirementMatch(requirement.capability(), mcpResourceUri(best));
    }

    private String mcpResourceUri(McpTool tool) {
        McpServer server = tool.getMcpServer();
        String uri = "voyager://mcp/" + server.getServerId() + "/" + tool.getToolName();
        McpTrustLevel trustLevel = server.getTrustLevel() == null
                ? McpTrustLevel.UNTRUSTED
                : server.getTrustLevel();
        if (trustLevel == McpTrustLevel.WRITE || trustLevel == McpTrustLevel.DESTRUCTIVE) {
            uri += "?trust=" + trustLevel;
        }
        return uri;
    }

    private Set<String> tokens(String value) {
        return Arrays.stream(normalize(value).split(" "))
                .filter(token -> token.length() >= 3)
                .map(this::singularToken)
                .filter(token -> !MATCH_STOP_WORDS.contains(token))
                .collect(Collectors.toSet());
    }

    private String singularToken(String token) {
        return token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")
                ? token.substring(0, token.length() - 1)
                : token;
    }

    private String normalize(String value) {
        return safe(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record McpRequirementMatch(String capability, String resourceUri) {
    }

    public record CatalogSearchResult(
            String uri,
            String type,
            String description,
            String argumentsSchema,
            String trust
    ) {
    }

    private record ScoredCatalogResult(
            CatalogSearchResult result,
            int score,
            int matchScore
    ) {
    }

    private void appendDescription(StringBuilder documentation, String description) {
        if (description != null && !description.isBlank()) {
            documentation.append(" — ").append(description.trim());
        }
    }

    private String flattenJsonSchema(String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank()) {
            return "{}";
        }
        try {
            JsonNode properties = objectMapper.readTree(jsonSchema).path("properties");
            if (!properties.isObject()) {
                return "{}";
            }
            StringBuilder arguments = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                if (!first) {
                    arguments.append(", ");
                }
                JsonNode type = property.getValue().path("type");
                arguments.append(property.getKey())
                        .append(':')
                        .append(type.isTextual() ? type.asText() : "any");
                first = false;
            }
            return arguments.append('}').toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
