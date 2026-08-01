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
            "results", "service", "tool"
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
                .filter(token -> !MATCH_STOP_WORDS.contains(token))
                .collect(Collectors.toSet());
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
