package com.job.scheduler.service;

import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.dto.FunctionLanguageDTO;
import com.job.scheduler.dto.WorkflowAiMcpRequirementDTO;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.repository.FunctionDefinitionRepository;
import com.job.scheduler.repository.McpToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowAiResourceCatalogServiceTest {
    @Mock
    private FunctionDefinitionRepository functionRepository;
    @Mock
    private McpToolRepository mcpToolRepository;
    @Mock
    private FunctionRuntimePolicy functionRuntimePolicy;
    @Mock
    private WorkflowAiEmbeddingService embeddingService;

    private WorkflowAiResourceCatalogService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowAiResourceCatalogService(
                functionRepository,
                mcpToolRepository,
                functionRuntimePolicy,
                new ObjectMapper(),
                embeddingService
        );
        // These tests exercise the full-detail catalog; retrieval tiering is covered separately.
        lenient().when(embeddingService.retrievalActive(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(false);
        lenient().when(functionRuntimePolicy.supportedSelectableLanguages())
                .thenReturn(List.of());
        lenient().when(functionRuntimePolicy.aiDefaultLanguage())
                .thenReturn(new FunctionLanguageDTO(71, "Python (3.8.1)", true));
    }

    @Test
    void documentsOnlyInvokableFunctionsAndEnabledServers() {
        FunctionDefinition enabledFunction = function("normalize-order", FunctionStatus.ENABLED, 2);
        enabledFunction.setDescription("Normalizes incoming order fields");
        FunctionDefinition unpublishedFunction = function("draft-only", FunctionStatus.ENABLED, null);
        when(functionRepository.findByStatusNotOrderByUpdatedAtDesc(FunctionStatus.ARCHIVED))
                .thenReturn(List.of(enabledFunction, unpublishedFunction));

        McpServer readServer = server("crm", McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        McpTool readTool = tool(readServer, "get-customer", "Loads one customer");
        readTool.setInputSchema("""
                {"type":"object","properties":{"customerId":{"type":"string"}}}
                """);
        McpServer writeServer = server("fulfillment", McpServerStatus.ENABLED, McpTrustLevel.WRITE);
        McpTool writeTool = tool(writeServer, "reserve-inventory", "Reserves order stock");
        McpServer disabledServer = server("disabled", McpServerStatus.DISABLED, McpTrustLevel.READ_ONLY);
        McpTool disabledTool = tool(disabledServer, "hidden-tool", "Must not be visible");
        when(mcpToolRepository.findByEnabledTrue())
                .thenReturn(List.of(readTool, writeTool, disabledTool));

        String catalog = service.buildCatalog();

        assertThat(catalog)
                .contains("SYSTEM RESOURCES:")
                .contains("voyager://system/webhook args: {url:string, method:string optional default POST")
                .contains("headers:object<string,string> optional")
                .contains("GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS")
                .contains("Each MCP args object is the Task Arguments shape")
                .contains("do not wrap them in payload")
                .contains("$states.result.structuredContent")
                .contains("voyager://function/normalize-order@v2")
                .contains("voyager://mcp/crm/get-customer [trust: READ_ONLY]")
                .contains("args: {customerId:string}")
                .contains("voyager://mcp/fulfillment/reserve-inventory?trust=WRITE")
                .doesNotContain("draft-only")
                .doesNotContain("hidden-tool")
                .doesNotContain("SUPPORTED FUNCTION LANGUAGES");
        assertThat(service.buildFunctionCreationContext())
                .contains("AI DEFAULT FUNCTION LANGUAGE")
                .contains("71", "Python");
    }

    @Test
    void matchesProviderSuggestionToItsDiscoveredSearchTool() {
        McpServer tavily = server(
                "tavily-web-search",
                McpServerStatus.ENABLED,
                McpTrustLevel.READ_ONLY
        );
        McpTool search = tool(
                tavily,
                "tavily_search",
                "Search the web for current information on any topic"
        );
        when(mcpToolRepository.findByEnabledTrue()).thenReturn(List.of(search));

        var matches = service.findMcpRequirementMatches(List.of(
                new WorkflowAiMcpRequirementDTO(
                        "search the web for a company name",
                        "tavily-web-search",
                        "extract the title of the top result",
                        "READ_ONLY"
                )
        ));

        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.capability()).isEqualTo("search the web for a company name");
            assertThat(match.resourceUri())
                    .isEqualTo("voyager://mcp/tavily-web-search/tavily_search");
        });
    }

    @Test
    void exactResourceUriSelectsTheNamedToolInsteadOfASiblingTool() {
        McpServer tavily = server(
                "tavily-free-search",
                McpServerStatus.ENABLED,
                McpTrustLevel.READ_ONLY
        );
        McpTool crawl = tool(tavily, "tavily_crawl", "Crawl pages from a public website");
        McpTool research = tool(
                tavily,
                "tavily_research",
                "Research current information using public web search"
        );
        when(mcpToolRepository.findByEnabledTrue()).thenReturn(List.of(crawl, research));

        var matches = service.findMcpRequirementMatches(List.of(
                new WorkflowAiMcpRequirementDTO(
                        "fetch current weather",
                        "voyager://mcp/tavily-free-search/tavily_research",
                        "Retrieve current information for Mangaluru",
                        "UNTRUSTED"
                )
        ));

        assertThat(matches).singleElement().satisfies(match -> assertThat(match.resourceUri())
                .isEqualTo("voyager://mcp/tavily-free-search/tavily_research"));
    }

    @Test
    void doesNotMatchARequirementFromOnlyOneGenericWord() {
        McpServer crm = server("crm", McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        McpTool search = tool(crm, "search-customers", "Search customer records by name");
        when(mcpToolRepository.findByEnabledTrue()).thenReturn(List.of(search));

        var matches = service.findMcpRequirementMatches(List.of(
                new WorkflowAiMcpRequirementDTO(
                        "search the public web",
                        null,
                        "return the top result",
                        "READ_ONLY"
                )
        ));

        assertThat(matches).isEmpty();
    }

    @Test
    void doesNotTreatGenericWebSearchAsAProviderSpecificWeatherApi() {
        McpServer tavily = server(
                "tavily-free-search",
                McpServerStatus.ENABLED,
                McpTrustLevel.READ_ONLY
        );
        McpTool search = tool(
                tavily,
                "tavily_research",
                "Research current information on any topic using public web search"
        );
        when(mcpToolRepository.findByEnabledTrue()).thenReturn(List.of(search));

        var matches = service.findMcpRequirementMatches(List.of(
                new WorkflowAiMcpRequirementDTO(
                        "OpenWeatherMap API access",
                        "openweathermap",
                        "fetch current weather data using a credential",
                        "READ_ONLY"
                )
        ));

        assertThat(matches).isEmpty();
    }

    @Test
    void tiersMcpToolsByRelevanceWhenRetrievalActive() {
        McpServer srv = server("srv", McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);

        UUID relevantId = UUID.randomUUID();
        McpTool relevantTool = tool(srv, "relevant_tool", "Relevant detailed tool");
        relevantTool.setId(relevantId);
        relevantTool.setInputSchema("""
                {"type":"object","properties":{"foo":{"type":"string"}}}
                """);

        McpTool indexTool = tool(srv, "index_tool", "Index only tool");
        indexTool.setId(UUID.randomUUID());
        indexTool.setInputSchema("""
                {"type":"object","properties":{"bar":{"type":"string"}}}
                """);

        when(functionRepository.findByStatusNotOrderByUpdatedAtDesc(FunctionStatus.ARCHIVED))
                .thenReturn(List.of());
        when(mcpToolRepository.findByEnabledTrue())
                .thenReturn(List.of(relevantTool, indexTool));
        when(embeddingService.retrievalActive(anyInt())).thenReturn(true);
        when(embeddingService.selectRelevantResourceIds(
                eq(com.job.scheduler.enums.ResourceEmbeddingType.MCP_TOOL), eq("send a foo")))
                .thenReturn(Set.of(relevantId.toString()));
        when(embeddingService.selectRelevantResourceIds(
                eq(com.job.scheduler.enums.ResourceEmbeddingType.FUNCTION), eq("send a foo")))
                .thenReturn(Set.of());

        String catalog = service.buildCatalog("send a foo");

        // Relevant tool keeps full detail: argument schema and description.
        assertThat(catalog)
                .contains("voyager://mcp/srv/relevant_tool")
                .contains("args: {foo:string}")
                .contains("Relevant detailed tool");
        // Index-tier tool keeps only its URI — no argument schema, no description.
        assertThat(catalog).contains("voyager://mcp/srv/index_tool");
        assertThat(catalog).doesNotContain("Index only tool");
        assertThat(catalog).doesNotContain("bar:string");
    }

    @Test
    void searchesCatalogAsCompactStructuredResults() {
        McpServer mail = server("mail", McpServerStatus.ENABLED, McpTrustLevel.WRITE);
        McpTool send = tool(mail, "send-message", "Send an email message");
        send.setInputSchema("""
                {"type":"object","properties":{"to":{"type":"string"},"body":{"type":"string"}}}
                """);
        McpTool unrelated = tool(mail, "archive-record", "Archive an old record");
        unrelated.setInputSchema("{" + "\"type\":\"object\"}");
        McpTool read = tool(mail, "read_file", "Read the complete contents of one file");
        read.setInputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}");
        when(functionRepository.findByStatusNotOrderByUpdatedAtDesc(FunctionStatus.ARCHIVED))
                .thenReturn(List.of());
        when(mcpToolRepository.findByEnabledTrue()).thenReturn(List.of(send, unrelated, read));

        List<WorkflowAiResourceCatalogService.CatalogSearchResult> results =
                service.searchCatalog("send email", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).uri())
                .isEqualTo("voyager://mcp/mail/send-message?trust=WRITE");
        assertThat(results.get(0).argumentsSchema()).contains("to", "body");
        assertThat(results.get(0).trust()).isEqualTo("WRITE");

        assertThat(service.searchCatalog("reads a file", 1).get(0).uri())
                .isEqualTo("voyager://mcp/mail/read_file?trust=WRITE");
        assertThat(service.searchCatalog("reads a file", 8))
                .extracting(WorkflowAiResourceCatalogService.CatalogSearchResult::uri)
                .containsExactly("voyager://mcp/mail/read_file?trust=WRITE");
        assertThat(service.searchCatalog("fetch current weather for Mangaluru", 8))
                .isEmpty();
        assertThat(service.searchCatalog(
                "create an exact registered Voyager workflow Task", 8))
                .isEmpty();
    }

    @Test
    void evaluationInstructionsDoNotCreateFalseCatalogMatches() {
        McpServer files = server("filesystem", McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        McpTool search = tool(files, "search_files",
                "Recursively search for files and directories matching a pattern");
        McpTool readText = tool(files, "read_text_file",
                "Read the complete contents of a file as text");
        when(functionRepository.findByStatusNotOrderByUpdatedAtDesc(FunctionStatus.ARCHIVED))
                .thenReturn(List.of());
        when(mcpToolRepository.findByEnabledTrue()).thenReturn(List.of(search, readText));

        assertThat(service.searchCatalog(
                "Create a workflow that fetches the current weather for Mangaluru from a live "
                        + "weather service and returns it. If the live catalog has no matching "
                        + "tool, propose the missing MCP capability.", 8))
                .isEmpty();
        assertThat(service.searchCatalog(
                "Create a workflow that computes the SHA-256 hex digest of an input string. "
                        + "If the live catalog has no matching resource, propose a deterministic "
                        + "local function with complete JSON stdin/stdout code and tests.", 8))
                .isEmpty();
        assertThat(service.searchCatalog(
                "Create a workflow that calls OpenWeatherMap. The example credential is "
                        + "YOUR_API_KEY. Do not store credentials in generated code; use an MCP "
                        + "requirement when a credentialed external capability is missing.", 8))
                .isEmpty();
    }

    @Test
    void aSingleExactResourceIdentifierRemainsSearchable() {
        when(functionRepository.findByStatusNotOrderByUpdatedAtDesc(FunctionStatus.ARCHIVED))
                .thenReturn(List.of());
        when(mcpToolRepository.findByEnabledTrue()).thenReturn(List.of());

        assertThat(service.searchCatalog("webhook", 8))
                .extracting(WorkflowAiResourceCatalogService.CatalogSearchResult::uri)
                .containsExactly("voyager://system/webhook");
    }

    @Test
    void embeddingNeighbourStillRequiresLexicalEvidenceForToolSearch() {
        McpServer files = server("filesystem", McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        UUID nearestId = UUID.randomUUID();
        McpTool read = tool(files, "read_file", "Read the complete contents of one file");
        read.setId(nearestId);
        when(mcpToolRepository.findByEnabledTrue()).thenReturn(List.of(read));
        when(embeddingService.retrievalActive(anyInt())).thenReturn(true);
        when(embeddingService.selectRelevantResourceIds(
                eq(com.job.scheduler.enums.ResourceEmbeddingType.MCP_TOOL),
                eq("fetch current weather for Mangaluru")))
                .thenReturn(Set.of(nearestId.toString()));
        when(embeddingService.selectRelevantResourceIds(
                eq(com.job.scheduler.enums.ResourceEmbeddingType.FUNCTION),
                eq("fetch current weather for Mangaluru")))
                .thenReturn(Set.of());

        assertThat(service.searchCatalog("fetch current weather for Mangaluru", 8))
                .isEmpty();
    }

    @Test
    void readmeIntentPrefersTextReaderOverMediaAndDeprecatedReaders() {
        McpServer files = server("filesystem", McpServerStatus.ENABLED, McpTrustLevel.READ_ONLY);
        McpTool deprecated = tool(
                files, "read_file", "Read a file as text. DEPRECATED: Use read_text_file instead.");
        McpTool media = tool(
                files, "read_media_file", "Read a file as base64 media content.");
        McpTool text = tool(
                files, "read_text_file", "Read the complete contents of a file as text.");
        when(mcpToolRepository.findByEnabledTrue()).thenReturn(List.of(deprecated, media, text));

        assertThat(service.searchCatalog(
                "Create a workflow that reads README.md using the filesystem Task", 8))
                .extracting(WorkflowAiResourceCatalogService.CatalogSearchResult::uri)
                .containsExactly("voyager://mcp/filesystem/read_text_file");
    }

    private FunctionDefinition function(String name, FunctionStatus status, Integer activeVersion) {
        FunctionDefinition function = new FunctionDefinition();
        function.setName(name);
        function.setStatus(status);
        function.setActiveVersion(activeVersion);
        return function;
    }

    private McpServer server(
            String serverId,
            McpServerStatus status,
            McpTrustLevel trustLevel
    ) {
        McpServer server = new McpServer();
        server.setServerId(serverId);
        server.setStatus(status);
        server.setTrustLevel(trustLevel);
        return server;
    }

    private McpTool tool(McpServer server, String name, String description) {
        McpTool tool = new McpTool();
        tool.setMcpServer(server);
        tool.setToolName(name);
        tool.setDescription(description);
        tool.setEnabled(true);
        tool.setInputSchema("{\"type\":\"object\"}");
        return tool;
    }
}
