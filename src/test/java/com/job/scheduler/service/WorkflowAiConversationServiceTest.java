package com.job.scheduler.service;

import com.job.scheduler.dto.CreateWorkflowRequestDTO;
import com.job.scheduler.dto.CreateWorkflowRevisionRequestDTO;
import com.job.scheduler.dto.FunctionDefinitionResponseDTO;
import com.job.scheduler.dto.FunctionVersionResponseDTO;
import com.job.scheduler.dto.WorkflowAiConversationDetailDTO;
import com.job.scheduler.dto.WorkflowAiMcpRequirementDTO;
import com.job.scheduler.dto.WorkflowAiProposedFunctionDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.dto.WorkflowAiSaveWorkflowRequestDTO;
import com.job.scheduler.dto.WorkflowDefinitionResponseDTO;
import com.job.scheduler.dto.WorkflowResponseDTO;
import com.job.scheduler.dto.WorkflowAiWorkspaceRequestDTO;
import com.job.scheduler.dto.WorkflowAiWorkspaceSettingsDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.WorkflowAiConversation;
import com.job.scheduler.entity.WorkflowAiMessage;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.FunctionSourceMode;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.FunctionVersionStatus;
import com.job.scheduler.enums.WorkflowAiConversationStage;
import com.job.scheduler.enums.WorkflowAiMessageRole;
import com.job.scheduler.enums.WorkflowAiWorkspaceKind;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.repository.WorkflowAiConversationRepository;
import com.job.scheduler.repository.WorkflowAiMessageRepository;
import com.job.scheduler.workflow.asl.runtime.AslRuntimeCapabilityValidator;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslFunctionResourceValidator;
import com.job.scheduler.workflow.asl.validation.AslMcpResourceValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationCategory;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WorkflowAiConversationServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AiModelConfigService aiModelConfigService;
    @Mock
    private WorkflowAiModelResolver modelResolver;
    @Mock
    private WorkflowAiConversationRepository conversationRepository;
    @Mock
    private WorkflowAiMessageRepository messageRepository;
    @Mock
    private AslDefinitionValidator aslDefinitionValidator;
    @Mock
    private AslRuntimeCapabilityValidator runtimeCapabilityValidator;
    @Mock
    private AslMcpResourceValidator mcpResourceValidator;
    @Mock
    private AslFunctionResourceValidator functionResourceValidator;
    @Mock
    private WorkflowService workflowService;
    @Mock
    private WorkflowAiResourceCatalogService resourceCatalogService;
    @Mock
    private FunctionRegistryService functionRegistryService;
    @Mock
    private FunctionRuntimePolicy functionRuntimePolicy;
    @Mock
    private ChatLanguageModel chatLanguageModel;

    private WorkflowAiConversationService service;
    private AiModelConfig modelConfig;

    @BeforeEach
    void setUp() {
        service = new WorkflowAiConversationService(
                aiModelConfigService,
                modelResolver,
                conversationRepository,
                messageRepository,
                aslDefinitionValidator,
                runtimeCapabilityValidator,
                mcpResourceValidator,
                functionResourceValidator,
                workflowService,
                resourceCatalogService,
                functionRegistryService,
                functionRuntimePolicy,
                objectMapper
        );
        modelConfig = new AiModelConfig();
        modelConfig.setId(UUID.randomUUID());
        modelConfig.setDisplayName("qwen3");
        modelConfig.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_LOCAL);
        modelConfig.setBaseUrl("http://localhost:11434/v1");
        modelConfig.setModelName("qwen3:8b");
        modelConfig.setEnabled(true);
        lenient().when(aslDefinitionValidator.validate(any(JsonNode.class)))
                .thenReturn(new AslValidationResult(List.of()));
        lenient().when(runtimeCapabilityValidator.validate(any(JsonNode.class)))
                .thenReturn(List.of());
        lenient().when(mcpResourceValidator.validate(any(JsonNode.class)))
                .thenReturn(List.of());
        lenient().when(functionResourceValidator.validate(any(JsonNode.class)))
                .thenReturn(List.of());
        lenient().when(resourceCatalogService.buildCatalog())
                .thenReturn("FUNCTIONS:\nNone registered.\nMCP TOOLS:\nNone registered.");
        lenient().when(resourceCatalogService.buildFunctionCreationContext())
                .thenReturn("SUPPORTED FUNCTION LANGUAGES (languageId — name):\n- 71 — Python");
        stubGeneratedIds();
    }

    @Test
    void startConversationStoresTurnAndPromotesValidAsl() throws Exception {
        JsonNode definition = objectMapper.readTree("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}
                """);
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("""
                        {"stage":"ASL_READY","message":"ASL is ready.","aslDefinition":{"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}}
                        """)
        ));
        when(aslDefinitionValidator.validate(any(JsonNode.class)))
                .thenReturn(new AslValidationResult(List.of()));
        when(runtimeCapabilityValidator.validate(any(JsonNode.class)))
                .thenReturn(List.of());

        WorkflowAiResponseDTO response = service.startConversation(
                "send a daily digest",
                modelConfig.getId(),
                "2026-06-28T09:00:00+05:30",
                null
        );

        assertThat(response.conversationId()).isNotNull();
        assertThat(response.stage()).isEqualTo(WorkflowAiConversationStage.ASL_READY);
        assertThat(response.aslDefinition()).isEqualTo(definition);
        assertThat(response.validationIssues()).isEmpty();
        assertThat(firstUserMessage().getContent()).isEqualTo("send a daily digest");
        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().toString())
                .contains("User date/time context: 2026-06-28T09:00:00+05:30");
    }

    @Test
    void startConversationIncludesLiveFunctionAndMcpCatalog() {
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(resourceCatalogService.buildCatalog()).thenReturn("""
                FUNCTIONS:
                - voyager://function/normalize-order@v1
                MCP TOOLS:
                - voyager://mcp/crm/get-customer [trust: READ_ONLY]
                """);
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("""
                        {"stage":"COLLECTING_WORKFLOW_DETAILS","message":"Which customer ID should I use?"}
                        """)
        ));

        service.startConversation(
                "normalize an order and load its customer",
                modelConfig.getId(),
                null,
                null
        );

        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().toString())
                .contains("Available Voyager Task resources (current registry)")
                .contains("voyager://function/normalize-order@v1")
                .contains("voyager://mcp/crm/get-customer")
                .contains("State output becomes the next state's $states.input")
                .contains("$states.input.firstResult")
                .contains("ItemSelector is allowed for a JSONata Map state")
                .contains("Spell the reserved variable exactly as $states")
                .contains("never wrap them in payload unless payload is an advertised key")
                .contains("$states.result.structuredContent.<field>")
                .contains("Do not add a terminal Pass solely to end the workflow")
                .doesNotContain("ItemsPath, ItemSelector");
    }

    @Test
    void blankAssistantMessageUsesArtifactFallbackAndStillPromotesValidAsl() {
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("""
                        {"stage":"ASL_READY","message":"","aslDefinition":{"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}}
                        """)
        ));

        WorkflowAiResponseDTO response = service.startConversation(
                "create a workflow that succeeds",
                modelConfig.getId(),
                null,
                null
        );

        assertThat(response.stage()).isEqualTo(WorkflowAiConversationStage.ASL_READY);
        assertThat(response.message()).isEqualTo("Generated workflow definition.");
        assertThat(response.aslDefinition().path("StartAt").stringValue()).isEqualTo("Done");
        verify(chatLanguageModel).generate(anyList());
    }

    @Test
    void getConversationHidesLegacyModelContextFromUserMessages() {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        WorkflowAiMessage legacyUserMessage = message(
                conversation,
                WorkflowAiMessageRole.USER,
                "hi\n\nUser date/time context: 2026-07-17T10:37:32.037Z"
                        + "\n\nCurrent ASL in the user's editor:\n{\"StartAt\":\"Done\"}",
                null
        );
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of(legacyUserMessage));

        var detail = service.getConversation(conversationId);

        assertThat(detail.messages()).singleElement().satisfies(message ->
                assertThat(message.content()).isEqualTo("hi")
        );
    }

    @Test
    void conversationWorkspacePersistsAndRestoresDefinitionCanvasAndSettings() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        JsonNode definition = objectMapper.readTree("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}
                """);
        JsonNode canvasLayout = objectMapper.readTree("""
                {"Done":{"x":420,"y":180}}
                """);
        WorkflowAiWorkspaceSettingsDTO settings = new WorkflowAiWorkspaceSettingsDTO(
                "Saved chat workflow",
                "0 9 * * *",
                5,
                "saved-chat-workflow",
                "Asia/Kolkata"
        );
        String definitionText = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(definition);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));

        service.saveWorkspace(
                conversationId,
                new WorkflowAiWorkspaceRequestDTO(
                        definition,
                        definitionText,
                        canvasLayout,
                        settings
                )
        );

        assertThat(objectMapper.readTree(conversation.getDraftAsl())).isEqualTo(definition);
        assertThat(conversation.getWorkspaceDefinitionText()).isEqualTo(definitionText);
        assertThat(objectMapper.readTree(conversation.getCanvasLayout())).isEqualTo(canvasLayout);
        assertThat(objectMapper.readTree(conversation.getWorkspaceSettings()).path("name").stringValue())
                .isEqualTo("Saved chat workflow");
        verify(conversationRepository).saveAndFlush(conversation);

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of());
        var restored = service.getConversation(conversationId);

        assertThat(restored.aslDefinition()).isEqualTo(definition);
        assertThat(restored.workspaceDefinitionText()).isEqualTo(definitionText);
        assertThat(restored.canvasLayout()).isEqualTo(canvasLayout);
        assertThat(restored.workspaceSettings()).isEqualTo(settings);
    }

    @Test
    void deleteConversationRemovesMessagesThenConversation() {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));

        service.deleteConversation(conversationId);

        var inOrder = org.mockito.Mockito.inOrder(messageRepository, conversationRepository);
        inOrder.verify(messageRepository).deleteByConversationId(conversationId);
        inOrder.verify(conversationRepository).delete(conversation);
    }

    @Test
    void restoresStoredAiFunctionNamesAsKebabCase() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setStage(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        conversation.setResourcePlan(objectMapper.writeValueAsString(java.util.Map.of(
                "functions", List.of(java.util.Map.of(
                        "name", "shorten_and_title_case",
                        "description", "Format a title",
                        "languageId", 71,
                        "sourceCode", "print(1)"
                )),
                "mcpRequirements", List.of()
        )));
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of());

        var restored = service.getConversation(conversationId);

        assertThat(restored.resourcePlan().functions()).singleElement().satisfies(function ->
                assertThat(function.name()).isEqualTo("shorten-and-title-case")
        );
    }

    @Test
    void deleteMissingConversationRejects() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteConversation(conversationId))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        verify(messageRepository, never()).deleteByConversationId(any());
        verify(conversationRepository, never()).delete(any());
    }

    @Test
    void deleteAllConversationsRemovesMessagesThenConversations() {
        WorkflowAiConversation first = conversation(UUID.randomUUID());
        WorkflowAiConversation second = conversation(UUID.randomUUID());
        when(conversationRepository.findAllByWorkspaceKind(WorkflowAiWorkspaceKind.AI_CHAT))
                .thenReturn(List.of(first, second));

        service.deleteAllConversations();

        var inOrder = org.mockito.Mockito.inOrder(messageRepository, conversationRepository);
        inOrder.verify(messageRepository).deleteByConversationId(first.getId());
        inOrder.verify(messageRepository).deleteByConversationId(second.getId());
        inOrder.verify(conversationRepository).deleteAll(List.of(first, second));
    }

    @Test
    void createManualDraftPersistsWorkspaceWithoutModelOrChat() throws Exception {
        JsonNode definition = objectMapper.readTree("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}
                """);
        String definitionText = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(definition);
        JsonNode canvasLayout = objectMapper.readTree("""
                {"Done":{"x":120,"y":240}}
                """);
        WorkflowAiWorkspaceSettingsDTO settings = new WorkflowAiWorkspaceSettingsDTO(
                "Manual invoice draft",
                null,
                3,
                "manual-invoice-draft",
                "UTC"
        );

        var detail = service.createDraft(new WorkflowAiWorkspaceRequestDTO(
                definition,
                definitionText,
                canvasLayout,
                settings
        ));

        ArgumentCaptor<WorkflowAiConversation> draftCaptor =
                ArgumentCaptor.forClass(WorkflowAiConversation.class);
        verify(conversationRepository).saveAndFlush(draftCaptor.capture());
        WorkflowAiConversation draft = draftCaptor.getValue();
        assertThat(draft.getWorkspaceKind()).isEqualTo(WorkflowAiWorkspaceKind.MANUAL_DRAFT);
        assertThat(draft.getModelConfig()).isNull();
        assertThat(draft.getName()).isEqualTo("Manual invoice draft");
        assertThat(draft.getInitialInstruction()).isEmpty();
        assertThat(draft.getWorkspaceDefinitionText()).isEqualTo(definitionText);
        assertThat(objectMapper.readTree(draft.getDraftAsl())).isEqualTo(definition);
        assertThat(detail.id()).isEqualTo(draft.getId());
        assertThat(detail.modelConfigId()).isNull();
        assertThat(detail.messages()).isEmpty();
    }

    @Test
    void customNamesPersistIndependentlyForChatsAndDrafts() {
        UUID chatId = UUID.randomUUID();
        WorkflowAiConversation chat = conversation(chatId);
        when(conversationRepository.findByIdForUpdate(chatId)).thenReturn(Optional.of(chat));

        var renamedChat = service.renameConversation(chatId, "  Production alerts  ");

        assertThat(chat.getCustomName()).isEqualTo("Production alerts");
        assertThat(renamedChat.name()).isEqualTo("Production alerts");

        UUID draftId = UUID.randomUUID();
        WorkflowAiConversation draft = conversation(draftId);
        draft.setWorkspaceKind(WorkflowAiWorkspaceKind.MANUAL_DRAFT);
        when(conversationRepository.findByIdForUpdate(draftId)).thenReturn(Optional.of(draft));

        var renamedDraft = service.renameDraft(draftId, "Invoice approval draft");

        assertThat(draft.getCustomName()).isEqualTo("Invoice approval draft");
        assertThat(renamedDraft.name()).isEqualTo("Invoice approval draft");
        verify(conversationRepository).saveAndFlush(chat);
        verify(conversationRepository).saveAndFlush(draft);
    }

    @Test
    void renameRouteCannotCrossWorkspaceKinds() {
        UUID draftId = UUID.randomUUID();
        WorkflowAiConversation draft = conversation(draftId);
        draft.setWorkspaceKind(WorkflowAiWorkspaceKind.MANUAL_DRAFT);
        when(conversationRepository.findByIdForUpdate(draftId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.renameConversation(draftId, "Wrong route"))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        verify(conversationRepository, never()).saveAndFlush(draft);
    }

    @Test
    void firstAiTurnInManualDraftKeepsDraftIdAndAttachesModel() {
        UUID draftId = UUID.randomUUID();
        WorkflowAiConversation draft = conversation(draftId);
        draft.setWorkspaceKind(WorkflowAiWorkspaceKind.MANUAL_DRAFT);
        draft.setInitialInstruction("");
        draft.setModelConfig(null);
        when(conversationRepository.findByIdForUpdate(draftId)).thenReturn(Optional.of(draft));
        when(aiModelConfigService.resolveModel(modelConfig.getId())).thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"stage":"COLLECTING_WORKFLOW_DETAILS","message":"Which source should I use?"}
                """)));

        WorkflowAiResponseDTO response = service.continueConversation(
                draftId,
                "Add an AI enrichment step",
                modelConfig.getId(),
                null,
                null
        );

        assertThat(response.conversationId()).isEqualTo(draftId);
        assertThat(draft.getWorkspaceKind()).isEqualTo(WorkflowAiWorkspaceKind.MANUAL_DRAFT);
        assertThat(draft.getModelConfig()).isEqualTo(modelConfig);
        assertThat(draft.getInitialInstruction()).isEqualTo("Add an AI enrichment step");
    }

    @Test
    void identicalWorkspaceSaveDoesNotTouchConversation() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        JsonNode definition = objectMapper.readTree("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}
                """);
        JsonNode canvasLayout = objectMapper.readTree("""
                {"Done":{"x":420,"y":180}}
                """);
        WorkflowAiWorkspaceSettingsDTO settings = new WorkflowAiWorkspaceSettingsDTO(
                "Saved chat workflow",
                "0 9 * * *",
                5,
                "saved-chat-workflow",
                "Asia/Kolkata"
        );
        conversation.setDraftAsl(objectMapper.writeValueAsString(definition));
        String definitionText = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(definition);
        conversation.setWorkspaceDefinitionText(definitionText);
        conversation.setCanvasLayout(objectMapper.writeValueAsString(canvasLayout));
        conversation.setWorkspaceSettings(objectMapper.writeValueAsString(settings));
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));

        service.saveWorkspace(
                conversationId,
                new WorkflowAiWorkspaceRequestDTO(
                        definition,
                        definitionText,
                        canvasLayout,
                        settings
                )
        );

        verify(conversationRepository, never()).saveAndFlush(any());
    }

    @Test
    void invalidWorkspaceDefinitionIsRestoredWithoutReplacingLastValidAsl() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        String validAsl = """
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}""";
        conversation.setDraftAsl(validAsl);
        JsonNode invalidDefinition = objectMapper.readTree("""
                {"StartAt":"Broken","States":{"Broken":{"Type":"Pass","Result":{},"End":true}}}
                """);
        JsonNode canvasLayout = objectMapper.readTree("{} ");
        String invalidDefinitionText = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(invalidDefinition);
        WorkflowAiWorkspaceSettingsDTO settings = new WorkflowAiWorkspaceSettingsDTO(
                "Protected workflow",
                null,
                3,
                "protected-workflow",
                "UTC"
        );
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aslDefinitionValidator.validate(invalidDefinition)).thenReturn(
                new AslValidationResult(List.of(new AslValidationIssue(
                        "$.States.Broken.Result",
                        AslValidationCategory.DIALECT,
                        "JSONPATH_FIELD_NOT_SUPPORTED",
                        "Result is not supported in the JSONata dialect"
                )))
        );

        service.saveWorkspace(
                conversationId,
                new WorkflowAiWorkspaceRequestDTO(
                        invalidDefinition,
                        invalidDefinitionText,
                        canvasLayout,
                        settings
                )
        );

        assertThat(conversation.getDraftAsl()).isEqualTo(validAsl);
        assertThat(conversation.getWorkspaceDefinitionText()).isEqualTo(invalidDefinitionText);
        assertThat(objectMapper.readTree(conversation.getCanvasLayout())).isEqualTo(canvasLayout);
        verify(conversationRepository).saveAndFlush(conversation);
    }

    @Test
    void incompleteJsonEditorTextIsAutosavedWithoutReplacingLastValidAsl() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        String validAsl = """
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}""";
        conversation.setDraftAsl(validAsl);
        JsonNode canvasLayout = objectMapper.readTree("{} ");
        WorkflowAiWorkspaceSettingsDTO settings = new WorkflowAiWorkspaceSettingsDTO(
                "In-progress workflow",
                null,
                3,
                "in-progress-workflow",
                "UTC"
        );
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));

        service.saveWorkspace(
                conversationId,
                new WorkflowAiWorkspaceRequestDTO(
                        null,
                        "{\"StartAt\":",
                        canvasLayout,
                        settings
                )
        );

        assertThat(conversation.getDraftAsl()).isEqualTo(validAsl);
        assertThat(conversation.getWorkspaceDefinitionText()).isEqualTo("{\"StartAt\":");
        verify(conversationRepository).saveAndFlush(conversation);
    }

    @Test
    void invalidModelAslIsRepairedOnceBeforePromotion() throws Exception {
        JsonNode adaptiveCard = objectMapper.readTree("""
                {"type":"AdaptiveCard","body":[]}
                """);
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(aslDefinitionValidator.validate(adaptiveCard)).thenReturn(
                new AslValidationResult(List.of(new AslValidationIssue(
                        "$",
                        AslValidationCategory.ASL,
                        "START_AT_REQUIRED",
                        "StartAt is required"
                )))
        );
        when(chatLanguageModel.generate(anyList())).thenReturn(
                Response.from(AiMessage.from("""
                        {"stage":"ASL_READY","message":"ready","aslDefinition":{"type":"AdaptiveCard","body":[]}}
                        """)),
                Response.from(AiMessage.from("""
                        {"stage":"ASL_READY","message":"repaired","aslDefinition":{"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}}
                        """))
        );

        WorkflowAiResponseDTO response = service.startConversation(
                "create a workflow that succeeds",
                modelConfig.getId(),
                null,
                null
        );

        assertThat(response.stage()).isEqualTo(WorkflowAiConversationStage.ASL_READY);
        assertThat(response.validationIssues()).isEmpty();
        assertThat(response.aslDefinition().path("StartAt").stringValue()).isEqualTo("Done");
        verify(chatLanguageModel, times(2)).generate(anyList());
    }

    @Test
    void rejectedModelAslAfterTwoRepairAttemptsNeverReplacesAuthoritativeDraft() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        String validAsl = """
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}""";
        conversation.setDraftAsl(validAsl);
        JsonNode invalidDefinition = objectMapper.readTree("""
                {"StartAt":"Broken","States":{"Broken":{"Type":"Pass","OutputPath":"$","End":true}}}
                """);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of());
        when(aslDefinitionValidator.validate(invalidDefinition)).thenReturn(
                new AslValidationResult(List.of(new AslValidationIssue(
                        "$.States.Broken.OutputPath",
                        AslValidationCategory.DIALECT,
                        "JSONPATH_FIELD_NOT_SUPPORTED",
                        "OutputPath is not supported in the JSONata dialect"
                )))
        );
        String rejectedResponse = """
                {"stage":"ASL_READY","message":"ready","aslDefinition":{"StartAt":"Broken","States":{"Broken":{"Type":"Pass","OutputPath":"$","End":true}}}}
                """;
        when(chatLanguageModel.generate(anyList())).thenReturn(
                Response.from(AiMessage.from(rejectedResponse)),
                Response.from(AiMessage.from(rejectedResponse)),
                Response.from(AiMessage.from(rejectedResponse))
        );

        WorkflowAiResponseDTO response = service.continueConversation(
                conversationId,
                "change the workflow",
                modelConfig.getId(),
                null
        );

        assertThat(response.stage()).isEqualTo(WorkflowAiConversationStage.ASL_UNDER_REVIEW);
        assertThat(response.validationIssues())
                .anyMatch(issue -> issue.contains("JSONPATH_FIELD_NOT_SUPPORTED"));
        assertThat(response.aslDefinition().path("StartAt").stringValue()).isEqualTo("Done");
        assertThat(objectMapper.readTree(conversation.getDraftAsl()).path("StartAt").stringValue())
                .isEqualTo("Done");
        verify(chatLanguageModel, times(3)).generate(anyList());
    }

    @Test
    void startConversationSeedsEditorAslIntoPrompt() throws Exception {
        JsonNode editorDefinition = objectMapper.readTree("""
                {"StartAt":"FetchOrder","States":{"FetchOrder":{"Type":"Pass","End":true}}}
                """);
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("""
                        {"stage":"COLLECTING_WORKFLOW_DETAILS","message":"Which task should retry?"}
                        """)
        ));

        service.startConversation(
                "add a retry to the fetch step",
                modelConfig.getId(),
                null,
                editorDefinition
        );

        WorkflowAiMessage userMessage = firstUserMessage();
        assertThat(userMessage.getContent()).isEqualTo("add a retry to the fetch step");
        assertThat(userMessage.getStructuredPayload()).contains("FetchOrder");
        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().toString())
                .contains("Current ASL in the user's editor")
                .contains("FetchOrder");
    }

    @Test
    void startConversationOmitsEditorAslWhenDefinitionHasNoStates() throws Exception {
        JsonNode emptyDefinition = objectMapper.readTree("""
                {"StartAt":"","States":{}}
                """);
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("""
                        {"stage":"COLLECTING_WORKFLOW_DETAILS","message":"What should it do?"}
                        """)
        ));

        service.startConversation("build me a workflow", modelConfig.getId(), null, emptyDefinition);

        assertThat(firstUserMessage().getContent())
                .doesNotContain("Current ASL in the user's editor");
    }

    @Test
    void continueConversationSkipsEditorAslThatMatchesKnownDraft() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        String storedAsl = """
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}""";
        conversation.setDraftAsl(storedAsl);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("""
                        {"stage":"COLLECTING_WORKFLOW_DETAILS","message":"Sure."}
                        """)
        ));

        service.continueConversation(
                conversationId,
                "what does this do?",
                modelConfig.getId(),
                objectMapper.readTree(storedAsl)
        );

        assertThat(firstUserMessage().getContent())
                .isEqualTo("what does this do?");
    }

    @Test
    void continueConversationResendsEditorAslAfterHandEdits() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setDraftAsl("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}""");
        JsonNode handEdited = objectMapper.readTree("""
                {"StartAt":"Wait","States":{"Wait":{"Type":"Wait","Seconds":5,"End":true}}}
                """);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("""
                        {"stage":"COLLECTING_WORKFLOW_DETAILS","message":"Got it."}
                        """)
        ));

        service.continueConversation(
                conversationId,
                "now alert me when it finishes",
                modelConfig.getId(),
                handEdited
        );

        assertThat(firstUserMessage().getContent())
                .isEqualTo("now alert me when it finishes");
        assertThat(conversation.getDraftAsl()).contains("Wait");
        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().toString())
                .contains("Latest ASL definition (authoritative)")
                .contains("Wait");
    }

    @Test
    void continueConversationForwardsIncompleteEditorBufferToModel() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setDraftAsl("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}""");
        String incompleteBuffer = "{\"StartAt\":\"Wait\",\"States\":{\"Wait\":{\"Type\":\"Wai";
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("""
                        {"stage":"COLLECTING_WORKFLOW_DETAILS","message":"I see your edit."}
                        """)
        ));

        service.continueConversation(
                conversationId,
                "help me finish this",
                modelConfig.getId(),
                null,
                incompleteBuffer
        );

        // The last valid ASL stays authoritative; the incomplete buffer is not promoted to draftAsl.
        assertThat(conversation.getDraftAsl()).doesNotContain("Wai\"");
        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().toString())
                .contains("Current editor buffer (work in progress")
                .contains("not valid JSON yet")
                .contains("\"Type\":\"Wai");
    }

    @Test
    void invalidEditorCandidateIsExplainedButNotMadeAuthoritative() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setDraftAsl("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}""");
        JsonNode invalidEditorDefinition = objectMapper.readTree("""
                {"StartAt":"Changed","States":{"Changed":{"Type":"Pass","ResultPath":"$","End":true}}}
                """);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of());
        when(aslDefinitionValidator.validate(invalidEditorDefinition)).thenReturn(
                new AslValidationResult(List.of(new AslValidationIssue(
                        "$.States.Changed.ResultPath",
                        AslValidationCategory.DIALECT,
                        "JSONPATH_FIELD_NOT_SUPPORTED",
                        "ResultPath is not supported in the JSONata dialect"
                )))
        );
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("""
                        {"stage":"ASL_UNDER_REVIEW","message":"Remove ResultPath."}
                        """)
        ));

        service.continueConversation(
                conversationId,
                "review my edit",
                modelConfig.getId(),
                invalidEditorDefinition
        );

        assertThat(objectMapper.readTree(conversation.getDraftAsl()).path("StartAt").stringValue())
                .isEqualTo("Done");
        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().toString())
                .contains("Candidate ASL in the user's editor (not authoritative)")
                .contains("ResultPath")
                .contains("JSONPATH_FIELD_NOT_SUPPORTED")
                .contains("Latest ASL definition (authoritative)")
                .contains("Done");
    }

    @Test
    void regeneratingARetryExcludesEarlierDiscardedRepliesFromThePrompt() {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);

        // Distinctive tokens: the system prompt is prose, so short words would match by accident.
        WorkflowAiMessage userMessage =
                message(conversation, WorkflowAiMessageRole.USER, "ORIGINAL_USER_ASK", null);
        WorkflowAiMessage firstReply =
                message(conversation, WorkflowAiMessageRole.ASSISTANT, "DISCARDED_ATTEMPT_ONE", null);
        // The reply being retried is itself a retry of firstReply.
        WorkflowAiMessage secondReply =
                message(conversation, WorkflowAiMessageRole.ASSISTANT, "DISCARDED_ATTEMPT_TWO", firstReply);

        when(messageRepository.findById(secondReply.getId()))
                .thenReturn(Optional.of(secondReply));
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findFirstByConversationOrderByCreatedAtDesc(conversation))
                .thenReturn(Optional.of(secondReply));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of(userMessage, firstReply, secondReply));
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("{\"stage\":\"COLLECTING_WORKFLOW_DETAILS\",\"message\":\"third attempt\"}")
        ));

        service.regenerateMessage(secondReply.getId(), modelConfig.getId());

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(captor.capture());
        String prompt = captor.getValue().toString();
        assertThat(prompt).contains("ORIGINAL_USER_ASK");
        assertThat(prompt).doesNotContain("DISCARDED_ATTEMPT_ONE");
        assertThat(prompt).doesNotContain("DISCARDED_ATTEMPT_TWO");
    }

    @Test
    void regeneratingAnOlderAssistantMessageIsRejected() {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        WorkflowAiMessage olderReply =
                message(conversation, WorkflowAiMessageRole.ASSISTANT, "OLDER_REPLY", null);
        WorkflowAiMessage latestReply =
                message(conversation, WorkflowAiMessageRole.ASSISTANT, "LATEST_REPLY", null);

        when(messageRepository.findById(olderReply.getId()))
                .thenReturn(Optional.of(olderReply));
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findFirstByConversationOrderByCreatedAtDesc(conversation))
                .thenReturn(Optional.of(latestReply));

        assertThatThrownBy(() -> service.regenerateMessage(
                olderReply.getId(),
                modelConfig.getId()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only the latest assistant message can be regenerated");

        verify(modelResolver, never()).resolve(any());
    }

    @Test
    void continuingConversationUsesLatestRetryAndAuthoritativeAsl() {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setDraftAsl(
                "{\"StartAt\":\"KeepThisState\",\"States\":{\"KeepThisState\":{\"Type\":\"Succeed\"}}}"
        );
        WorkflowAiMessage originalUser = message(
                conversation,
                WorkflowAiMessageRole.USER,
                "ORIGINAL_REQUIREMENT",
                null
        );
        WorkflowAiMessage discardedReply = message(
                conversation,
                WorkflowAiMessageRole.ASSISTANT,
                "DISCARDED_REPLY",
                null
        );
        WorkflowAiMessage activeRetry = message(
                conversation,
                WorkflowAiMessageRole.ASSISTANT,
                "ACTIVE_RETRY_REPLY",
                discardedReply
        );
        WorkflowAiMessage followUp = message(
                conversation,
                WorkflowAiMessageRole.USER,
                "CURRENT_FOLLOW_UP",
                null
        );

        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of(originalUser, discardedReply, activeRetry, followUp));
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("{\"stage\":\"COLLECTING_WORKFLOW_DETAILS\",\"message\":\"continued\"}")
        ));

        service.continueConversation(
                conversationId,
                "CURRENT_FOLLOW_UP",
                modelConfig.getId(),
                null
        );

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(captor.capture());
        String prompt = captor.getValue().toString();
        assertThat(prompt)
                .contains("ORIGINAL_REQUIREMENT")
                .contains("ACTIVE_RETRY_REPLY")
                .contains("CURRENT_FOLLOW_UP")
                .contains("Latest ASL definition (authoritative)")
                .contains("KeepThisState")
                .doesNotContain("DISCARDED_REPLY");
    }

    @Test
    void longConversationPersistsSummaryAndKeepsRecentTurnsVerbatim() {
        ReflectionTestUtils.setField(service, "maximumContextTokens", 700);
        ReflectionTestUtils.setField(service, "recentContextTokens", 120);
        ReflectionTestUtils.setField(service, "maximumSummaryCharacters", 1000);

        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setDraftAsl(
                "{\"StartAt\":\"CurrentState\",\"States\":{\"CurrentState\":{\"Type\":\"Succeed\"}}}"
        );
        WorkflowAiMessage oldUser = message(
                conversation,
                WorkflowAiMessageRole.USER,
                "OLD_REQUIREMENT_" + "A".repeat(1200),
                null
        );
        WorkflowAiMessage oldAssistant = message(
                conversation,
                WorkflowAiMessageRole.ASSISTANT,
                "OLD_DECISION_" + "B".repeat(1200),
                null
        );
        WorkflowAiMessage currentUser = message(
                conversation,
                WorkflowAiMessageRole.USER,
                "CURRENT_FOLLOW_UP",
                null
        );

        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of(oldUser, oldAssistant, currentUser));
        when(chatLanguageModel.generate(anyList())).thenReturn(
                Response.from(AiMessage.from(
                        "The user confirmed the old requirement and the assistant recorded the old decision."
                )),
                Response.from(AiMessage.from(
                        "{\"stage\":\"COLLECTING_WORKFLOW_DETAILS\",\"message\":\"continued with context\"}"
                ))
        );

        service.continueConversation(
                conversationId,
                "CURRENT_FOLLOW_UP",
                modelConfig.getId(),
                null
        );

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel, times(2)).generate(captor.capture());
        List<List<ChatMessage>> calls = captor.getAllValues();
        assertThat(calls.get(0).toString())
                .contains("OLD_REQUIREMENT_")
                .contains("OLD_DECISION_");
        assertThat(calls.get(1).toString())
                .contains("Summary of earlier conversation turns")
                .contains("old requirement")
                .contains("CURRENT_FOLLOW_UP")
                .contains("Latest ASL definition (authoritative)")
                .contains("CurrentState")
                .contains("Source anchors (authoritative excerpts)")
                .contains("OLD_REQUIREMENT_A…")
                .contains("OLD_DECISION_B…")
                .doesNotContain("A".repeat(100))
                .doesNotContain("B".repeat(100));
        assertThat(conversation.getConversationSummary())
                .contains("old requirement")
                .contains("OLD_REQUIREMENT_A…")
                .contains("OLD_DECISION_B…");
        assertThat(conversation.getSummarizedThroughMessageId())
                .isEqualTo(oldAssistant.getId());
        verify(conversationRepository).save(conversation);
    }

    @Test
    void ungroundedSummaryFallsBackToSourceAnchorsAndPreservesExactMarkers() {
        ReflectionTestUtils.setField(service, "maximumContextTokens", 600);
        ReflectionTestUtils.setField(service, "recentContextTokens", 100);
        ReflectionTestUtils.setField(service, "maximumSummaryCharacters", 2400);

        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        WorkflowAiMessage alpha = message(
                conversation,
                WorkflowAiMessageRole.USER,
                "Remember exact marker ALPHA-17. " + "0".repeat(1800),
                null
        );
        WorkflowAiMessage bravo = message(
                conversation,
                WorkflowAiMessageRole.ASSISTANT,
                "Confirmed exact marker BRAVO-29. " + "1".repeat(1800),
                null
        );
        WorkflowAiMessage charlie = message(
                conversation,
                WorkflowAiMessageRole.USER,
                "Remember exact marker CHARLIE-43. " + "2".repeat(1800),
                null
        );
        WorkflowAiMessage latest = message(
                conversation,
                WorkflowAiMessageRole.USER,
                "Recall ALPHA-17, BRAVO-29, CHARLIE-43 and DELTA-61.",
                null
        );
        conversation.setConversationSummary(
                "It seems like the user accidentally typed many zero characters."
        );
        conversation.setSummarizedThroughMessageId(bravo.getId());
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of(alpha, bravo, charlie, latest));
        when(chatLanguageModel.generate(anyList())).thenReturn(
                Response.from(AiMessage.from(
                        "It seems like the user accidentally typed many zero characters."
                )),
                Response.from(AiMessage.from("""
                        {"stage":"ASL_READY","message":"Context retained."}
                        """))
        );

        service.continueConversation(
                conversationId,
                "Recall ALPHA-17, BRAVO-29, CHARLIE-43 and DELTA-61.",
                modelConfig.getId(),
                null
        );

        assertThat(conversation.getConversationSummary())
                .contains("Source anchors (authoritative excerpts)")
                .contains("ALPHA-17")
                .contains("BRAVO-29")
                .contains("CHARLIE-43")
                .doesNotContain("accidentally typed many zero characters");
        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel, times(2)).generate(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(1).toString())
                .contains("Exact source identifiers (verbatim)")
                .contains("ALPHA-17")
                .contains("BRAVO-29")
                .contains("CHARLIE-43")
                .contains("DELTA-61")
                .doesNotContain("0".repeat(100));
    }

    @Test
    void emptyAssistantReplyFailsInsteadOfPersistingABlankMessage() {
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("")
        ));

        assertThatThrownBy(() -> service.startConversation(
                "build a workflow",
                modelConfig.getId(),
                null,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty reply");

        verify(messageRepository, never()).saveAndFlush(argThat(
                message -> message.getRole() == WorkflowAiMessageRole.ASSISTANT
        ));
    }

    @Test
    void malformedAssistantJsonIsNotStoredAsStructuredPayload() {
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        // Small local models routinely emit not-quite-JSON; structured_payload is a json column,
        // so storing it raw used to fail the insert and lose the whole turn.
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("{\"message\":\"here\",\"Fn::Equals\":[{\"States.TaskFailed\"}]}")
        ));

        service.startConversation("build a retry", modelConfig.getId(), null, null);

        WorkflowAiMessage assistantMessage = firstMessageWithRole(WorkflowAiMessageRole.ASSISTANT);
        assertThat(assistantMessage.getStructuredPayload()).isNull();
        assertThat(assistantMessage.getContent()).contains("Fn::Equals");
    }

    @Test
    void reviewAslReturnsValidationIssuesAndKeepsReviewStage() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        JsonNode definition = objectMapper.readTree("""
                {"StartAt":"Broken","States":{}}
                """);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("""
                        {"stage":"ASL_UNDER_REVIEW","message":"Fix the StartAt target."}
                        """)
        ));
        when(aslDefinitionValidator.validate(definition)).thenReturn(
                new AslValidationResult(List.of(new AslValidationIssue(
                        "$.StartAt",
                        AslValidationCategory.ASL,
                        "START_AT_TARGET_MISSING",
                        "StartAt must name an existing state"
                )))
        );
        when(runtimeCapabilityValidator.validate(definition)).thenReturn(List.of());

        WorkflowAiResponseDTO response = service.reviewAsl(conversationId, definition);

        assertThat(response.stage())
                .isEqualTo(WorkflowAiConversationStage.ASL_UNDER_REVIEW);
        assertThat(response.validationIssues())
                .anyMatch(issue -> issue.contains("START_AT_TARGET_MISSING"));
    }

    @Test
    void acceptPlanCreatesDraftWorkflowFromReviewedAsl() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setDraftAsl("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}
                """);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        WorkflowResponseDTO workflow = new WorkflowResponseDTO(
                workflowId,
                0,
                conversation.getName(),
                WorkflowStatus.DRAFT,
                null,
                "UTC",
                null,
                3,
                "workflow-ai-" + conversationId,
                null,
                Instant.now(),
                Instant.now()
        );
        when(workflowService.createWorkflow(any(CreateWorkflowRequestDTO.class)))
                .thenReturn(workflow);

        WorkflowAiResponseDTO response = service.acceptPlan(conversationId);

        assertThat(response.stage()).isEqualTo(WorkflowAiConversationStage.ACCEPTED);
        assertThat(response.workflowId()).isEqualTo(workflowId);
        assertThat(conversation.getWorkflowId()).isEqualTo(workflowId);
        ArgumentCaptor<CreateWorkflowRequestDTO> requestCaptor =
                ArgumentCaptor.forClass(CreateWorkflowRequestDTO.class);
        verify(workflowService).createWorkflow(requestCaptor.capture());
        assertThat(requestCaptor.getValue().definition().path("StartAt").stringValue())
                .isEqualTo("Done");
    }

    @Test
    void firstConversationSaveLinksWorkflowAndStillCreatesRevisionForLegacyChat()
            throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        JsonNode definition = objectMapper.readTree("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}
                """);
        JsonNode canvasLayout = objectMapper.readTree("{} ");
        WorkflowAiConversation conversation = conversation(conversationId);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        WorkflowResponseDTO workflow = workflow(
                workflowId,
                WorkflowStatus.ACTIVE,
                null,
                definition
        );
        WorkflowDefinitionResponseDTO revision = revision(workflowId, 2, definition, true);
        when(workflowService.createWorkflow(any(CreateWorkflowRequestDTO.class)))
                .thenReturn(workflow);
        when(workflowService.createRevision(eq(workflowId), any()))
                .thenReturn(revision);
        when(workflowService.updateCanvasLayout(eq(workflowId), eq(2L), any()))
                .thenReturn(revision);
        when(workflowService.getWorkflow(workflowId)).thenReturn(workflow);

        var result = service.saveConversationWorkflow(
                conversationId,
                new WorkflowAiSaveWorkflowRequestDTO(
                        new CreateWorkflowRequestDTO(
                                "Daily digest",
                                null,
                                3,
                                "workflow-ai-" + conversationId,
                                "UTC",
                                definition
                        ),
                        canvasLayout
                )
        );

        assertThat(result.workflow().id()).isEqualTo(workflowId);
        assertThat(result.revision().revision()).isEqualTo(2);
        assertThat(conversation.getWorkflowId()).isEqualTo(workflowId);
        assertThat(conversation.getStage()).isEqualTo(WorkflowAiConversationStage.ACCEPTED);
        verify(workflowService).createRevision(
                eq(workflowId),
                argThat(CreateWorkflowRevisionRequestDTO::activate)
        );
        verify(workflowService).updateCanvasLayout(
                eq(workflowId),
                eq(2L),
                argThat(request -> request.positions().equals(canvasLayout))
        );
    }

    @Test
    void laterConversationSaveCreatesRevisionWithoutRecreatingWorkflow() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        JsonNode definition = objectMapper.readTree("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}
                """);
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setWorkflowId(workflowId);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        WorkflowResponseDTO workflow = workflow(
                workflowId,
                WorkflowStatus.DRAFT,
                "0 0 9 * * *",
                definition
        );
        WorkflowDefinitionResponseDTO revision = revision(workflowId, 3, definition, false);
        when(workflowService.getWorkflow(workflowId)).thenReturn(workflow);
        when(workflowService.createRevision(eq(workflowId), any()))
                .thenReturn(revision);
        when(workflowService.updateCanvasLayout(eq(workflowId), eq(3L), any()))
                .thenReturn(revision);

        var result = service.saveConversationWorkflow(
                conversationId,
                new WorkflowAiSaveWorkflowRequestDTO(
                        new CreateWorkflowRequestDTO(
                                "Daily digest",
                                "0 0 9 * * *",
                                3,
                                "workflow-ai-" + conversationId,
                                "UTC",
                                definition
                        ),
                        objectMapper.readTree("{}")
                )
        );

        assertThat(result.revision().revision()).isEqualTo(3);
        verify(workflowService, never()).createWorkflow(any());
        verify(workflowService).createRevision(
                eq(workflowId),
                argThat(request -> !request.activate())
        );
    }

    @Test
    void laterDraftSaveCreatesRevisionWithoutDeletingOrRecreatingWorkspace() throws Exception {
        UUID draftId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        JsonNode definition = objectMapper.readTree("""
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}
                """);
        WorkflowAiConversation draft = conversation(draftId);
        draft.setWorkspaceKind(WorkflowAiWorkspaceKind.MANUAL_DRAFT);
        draft.setWorkflowId(workflowId);
        when(conversationRepository.findByIdForUpdate(draftId))
                .thenReturn(Optional.of(draft));
        WorkflowResponseDTO workflow = workflow(
                workflowId,
                WorkflowStatus.ACTIVE,
                null,
                definition
        );
        WorkflowDefinitionResponseDTO revision = revision(workflowId, 2, definition, true);
        when(workflowService.getWorkflow(workflowId)).thenReturn(workflow);
        when(workflowService.createRevision(eq(workflowId), any()))
                .thenReturn(revision);
        when(workflowService.updateCanvasLayout(eq(workflowId), eq(2L), any()))
                .thenReturn(revision);

        var result = service.saveDraftWorkflow(
                draftId,
                new WorkflowAiSaveWorkflowRequestDTO(
                        new CreateWorkflowRequestDTO(
                                "Daily digest",
                                null,
                                3,
                                "workflow-ai-" + draftId,
                                "UTC",
                                definition
                        ),
                        objectMapper.createObjectNode()
                )
        );

        assertThat(result.revision().revision()).isEqualTo(2);
        assertThat(draft.getWorkflowId()).isEqualTo(workflowId);
        verify(workflowService, never()).createWorkflow(any());
        verify(conversationRepository, never()).delete(draft);
    }

    private WorkflowResponseDTO workflow(
            UUID workflowId,
            WorkflowStatus status,
            String cronExpression,
            JsonNode definition
    ) {
        return new WorkflowResponseDTO(
                workflowId,
                1,
                "Daily digest",
                status,
                cronExpression,
                "UTC",
                null,
                3,
                "workflow-key",
                revision(workflowId, 1, definition, status == WorkflowStatus.ACTIVE),
                Instant.now(),
                Instant.now()
        );
    }

    private WorkflowDefinitionResponseDTO revision(
            UUID workflowId,
            long revision,
            JsonNode definition,
            boolean active
    ) {
        return new WorkflowDefinitionResponseDTO(
                UUID.nameUUIDFromBytes((workflowId + ":" + revision).getBytes()),
                revision,
                "hash-" + revision,
                definition,
                objectMapper.createObjectNode(),
                active,
                Instant.now()
        );
    }

    private WorkflowAiMessage firstUserMessage() {
        return firstMessageWithRole(WorkflowAiMessageRole.USER);
    }

    private WorkflowAiMessage firstMessageWithRole(WorkflowAiMessageRole role) {
        ArgumentCaptor<WorkflowAiMessage> captor =
                ArgumentCaptor.forClass(WorkflowAiMessage.class);
        verify(messageRepository, atLeastOnce()).saveAndFlush(captor.capture());
        return captor.getAllValues()
                .stream()
                .filter(message -> message.getRole() == role)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + role + " message was appended"));
    }

    private WorkflowAiMessage message(
            WorkflowAiConversation conversation,
            WorkflowAiMessageRole role,
            String content,
            WorkflowAiMessage regeneratedFrom
    ) {
        WorkflowAiMessage message = new WorkflowAiMessage();
        message.setId(UUID.randomUUID());
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content);
        message.setModelConfig(modelConfig);
        message.setRegeneratedFromMessage(regeneratedFrom);
        return message;
    }

    @Test
    void proposesResourcesWhenModelReturnsResourcePlan() {
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"stage":"RESOURCES_PROPOSED","message":"I need a helper function first.",
                 "resourcePlan":{"functions":[{"name":"normalize-address","description":"Cleans an address",
                 "languageId":71,"sourceCode":"import sys\\nprint(sys.stdin.read())",
                 "rationale":"no catalog entry cleans addresses"}],"mcpRequirements":[]}}
                """)));

        WorkflowAiResponseDTO response = service.startConversation(
                "normalize a postal address then post it",
                modelConfig.getId(),
                null,
                null
        );

        assertThat(response.stage())
                .isEqualTo(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        assertThat(response.aslDefinition()).isNull();
        assertThat(response.resourcePlan()).isNotNull();
        assertThat(response.resourcePlan().functions()).hasSize(1);
        assertThat(response.resourcePlan().functions().get(0).name())
                .isEqualTo("normalize-address");
        assertThat(response.resourcePlan().functions().get(0).languageId()).isEqualTo(71);
        assertThat(response.assistantMessage().resourcePlan()).isEqualTo(response.resourcePlan());
        assertThat(response.resourcePlanMessageId()).isEqualTo(response.assistantMessage().id());
        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel, times(2)).generate(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(0).toString())
                .doesNotContain("FUNCTION CREATION CONTRACT")
                .doesNotContain("SUPPORTED FUNCTION LANGUAGES")
                .doesNotContain("parse that JSON before transforming it");
        assertThat(promptCaptor.getAllValues().get(1).toString())
                .contains("FUNCTION CREATION CONTRACT")
                .contains("SUPPORTED FUNCTION LANGUAGES")
                .contains("parse that JSON before transforming it")
                .contains("lowercase kebab-case");
    }

    @Test
    void refreshUsesImmutableOriginalProposalInsteadOfOverwrittenProgressMetadata()
            throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setStage(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        WorkflowAiMessage owner = message(
                conversation,
                WorkflowAiMessageRole.ASSISTANT,
                "Attach search and Slack servers.",
                null
        );
        JsonNode originalPlan = objectMapper.readTree("""
                {"functions":[],"mcpRequirements":[
                  {"capability":"search the web","suggestedToolName":"tavily"},
                  {"capability":"send a Slack message","suggestedToolName":"slack"}
                ]}
                """);
        JsonNode remainingPlan = objectMapper.readTree("""
                {"functions":[],"mcpRequirements":[
                  {"capability":"send a Slack message","suggestedToolName":"slack"}
                ]}
                """);
        owner.setStructuredPayload(objectMapper.writeValueAsString(java.util.Map.of(
                "stage", "RESOURCES_PROPOSED",
                "resourcePlan", originalPlan
        )));
        // Simulates a row written by the previous implementation after search was attached.
        owner.setMetadataJson(objectMapper.writeValueAsString(java.util.Map.of(
                "resourcePlan", remainingPlan
        )));
        conversation.setResourcePlan(objectMapper.writeValueAsString(remainingPlan));
        conversation.setResourcePlanMessageId(owner.getId());

        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of(owner));

        WorkflowAiConversationDetailDTO detail = service.getConversation(conversationId);

        assertThat(detail.resourcePlan().mcpRequirements())
                .extracting(WorkflowAiMcpRequirementDTO::capability)
                .containsExactly("send a Slack message");
        assertThat(detail.messages()).singleElement().satisfies(message ->
                assertThat(message.resourcePlan().mcpRequirements())
                        .extracting(WorkflowAiMcpRequirementDTO::capability)
                        .containsExactly("search the web", "send a Slack message")
        );
    }

    @Test
    void partialMcpCompletionDoesNotOverwriteOriginalMessageAttachment()
            throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setStage(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        String originalPlan = objectMapper.writeValueAsString(java.util.Map.of(
                "functions", List.of(),
                "mcpRequirements", List.of(
                        java.util.Map.of(
                                "capability", "search the web",
                                "suggestedToolName", "tavily"
                        ),
                        java.util.Map.of(
                                "capability", "send a Slack message",
                                "suggestedToolName", "slack"
                        )
                )
        ));
        conversation.setResourcePlan(originalPlan);
        WorkflowAiMessage owner = message(
                conversation,
                WorkflowAiMessageRole.ASSISTANT,
                "Attach search and Slack servers.",
                null
        );
        owner.setStructuredPayload(objectMapper.writeValueAsString(java.util.Map.of(
                "stage", "RESOURCES_PROPOSED",
                "resourcePlan", objectMapper.readTree(originalPlan)
        )));
        owner.setMetadataJson(objectMapper.writeValueAsString(java.util.Map.of(
                "resourcePlan", objectMapper.readTree(originalPlan)
        )));
        String originalMetadata = owner.getMetadataJson();
        conversation.setResourcePlanMessageId(owner.getId());

        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(owner));
        when(messageRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        var searchMatch = new WorkflowAiResourceCatalogService.McpRequirementMatch(
                "search the web",
                "voyager://mcp/tavily/tavily_search"
        );
        when(resourceCatalogService.findMcpRequirementMatches(any()))
                .thenReturn(List.of(searchMatch), List.of());
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"stage":"RESOURCES_PROPOSED","message":"Attach Slack next.",
                 "resourcePlan":{"functions":[],"mcpRequirements":[{
                   "capability":"send a Slack message","suggestedToolName":"slack"
                 }]}}
                """)));

        WorkflowAiResponseDTO response = service.provisionResources(
                conversationId,
                List.of(),
                modelConfig.getId()
        );

        assertThat(response.resourcePlan().mcpRequirements())
                .extracting(WorkflowAiMcpRequirementDTO::capability)
                .containsExactly("send a Slack message");
        assertThat(owner.getMetadataJson()).isEqualTo(originalMetadata);
    }

    @Test
    void repairsResourcePlanWhenRequestedMcpCapabilityAlreadyExists() {
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        var match = new WorkflowAiResourceCatalogService.McpRequirementMatch(
                "search the web",
                "voyager://mcp/tavily/tavily_search"
        );
        when(resourceCatalogService.findMcpRequirementMatches(any()))
                .thenReturn(List.of(match), List.of());
        when(chatLanguageModel.generate(anyList()))
                .thenReturn(
                        Response.from(AiMessage.from("""
                                {"stage":"RESOURCES_PROPOSED","message":"Attach search first.",
                                 "resourcePlan":{"functions":[],"mcpRequirements":[
                                 {"capability":"search the web","suggestedToolName":"tavily"}]}}
                                """)),
                        Response.from(AiMessage.from("""
                                {"stage":"ASL_READY","message":"Using the attached search tool.",
                                 "aslDefinition":{"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}}
                                """))
                );

        WorkflowAiResponseDTO response = service.startConversation(
                "search the web",
                modelConfig.getId(),
                null,
                null
        );

        assertThat(response.stage()).isEqualTo(WorkflowAiConversationStage.ASL_READY);
        assertThat(response.resourcePlan()).isNull();
        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel, times(2)).generate(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(0).toString())
                .doesNotContain("FUNCTION CREATION CONTRACT");
        assertThat(promptCaptor.getAllValues().get(1).toString())
                .doesNotContain("FUNCTION CREATION CONTRACT");
    }

    @Test
    void repairsFunctionPlaceholderThatWasIncorrectlyProposedAsMcp() {
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(resourceCatalogService.findMcpRequirementMatches(any()))
                .thenReturn(List.of());
        when(chatLanguageModel.generate(anyList()))
                .thenReturn(
                        Response.from(AiMessage.from("""
                                {"stage":"RESOURCES_PROPOSED","message":"Need a title helper.",
                                 "resourcePlan":{"functions":[],"mcpRequirements":[{
                                 "capability":"shorten and title-case text",
                                 "suggestedToolName":"voyager://function/"}]}}
                                """)),
                        Response.from(AiMessage.from("""
                                {"stage":"RESOURCES_PROPOSED","message":"Review the title helper.",
                                 "resourcePlan":{"functions":[{"name":"format-title",
                                 "description":"Shortens and title-cases text","languageId":71,
                                 "sourceCode":"print(1)",
                                 "rationale":"deterministic string formatting"}],"mcpRequirements":[]}}
                                """))
                );

        WorkflowAiResponseDTO response = service.startConversation(
                "shorten and title-case text",
                modelConfig.getId(),
                null,
                null
        );

        assertThat(response.validationIssues()).isEmpty();
        assertThat(response.resourcePlan().functions()).hasSize(1);
        assertThat(response.resourcePlan().functions().get(0).name())
                .isEqualTo("format-title");
        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel, times(3)).generate(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(0).toString())
                .doesNotContain("FUNCTION CREATION CONTRACT");
        assertThat(promptCaptor.getAllValues().get(1).toString())
                .doesNotContain("FUNCTION CREATION CONTRACT");
        assertThat(promptCaptor.getAllValues().get(2).toString())
                .contains("FUNCTION CREATION CONTRACT")
                .contains("SUPPORTED FUNCTION LANGUAGES");
    }

    @Test
    void rejectedContinuationKeepsOriginalResourceCardAndSuppressesRejectedPlan()
            throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setStage(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        String originalPlan = objectMapper.writeValueAsString(java.util.Map.of(
                "functions", List.of(),
                "mcpRequirements", List.of(java.util.Map.of(
                        "capability", "search the web",
                        "suggestedToolName", "tavily"
                ))
        ));
        conversation.setResourcePlan(originalPlan);
        WorkflowAiMessage owner = message(
                conversation,
                WorkflowAiMessageRole.ASSISTANT,
                "Attach the search server.",
                null
        );
        owner.setStructuredPayload(objectMapper.writeValueAsString(java.util.Map.of(
                "stage", "RESOURCES_PROPOSED",
                "resourcePlan", objectMapper.readTree(originalPlan)
        )));

        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(owner));
        var match = new WorkflowAiResourceCatalogService.McpRequirementMatch(
                "search the web",
                "voyager://mcp/tavily/tavily_search"
        );
        when(resourceCatalogService.findMcpRequirementMatches(any()))
                .thenReturn(List.of(match));
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"stage":"RESOURCES_PROPOSED","message":"Attach search.",
                 "resourcePlan":{"functions":[],"mcpRequirements":[{
                 "capability":"search the web","suggestedToolName":"tavily"}]}}
                """)));

        WorkflowAiResponseDTO response = service.provisionResources(
                conversationId,
                List.of(),
                modelConfig.getId()
        );

        assertThat(response.validationIssues()).isNotEmpty();
        assertThat(response.resourcePlanMessageId()).isEqualTo(owner.getId());
        assertThat(response.resourcePlan()).isNotNull();
        assertThat(response.assistantMessage().resourcePlan()).isNull();
        assertThat(response.assistantMessage().content())
                .startsWith("I couldn't apply the generated change");
        assertThat(conversation.getResourcePlan()).isEqualTo(originalPlan);
        verify(chatLanguageModel, times(3)).generate(anyList());
    }

    @Test
    void resumeAfterMcpUsesExistingPlanWithoutAddingAUserTurn() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setStage(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        conversation.setResourcePlan(objectMapper.writeValueAsString(
                java.util.Map.of(
                        "functions", List.of(),
                        "mcpRequirements", List.of(java.util.Map.of(
                                "capability", "search the web",
                                "suggestedToolName", "tavily"
                        ))
                )
        ));
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(resourceCatalogService.findMcpRequirementMatches(any())).thenReturn(List.of(
                new WorkflowAiResourceCatalogService.McpRequirementMatch(
                        "search the web",
                        "voyager://mcp/tavily/tavily_search"
                )
        ));
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"stage":"ASL_READY","message":"Ready.",
                 "aslDefinition":{"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}}
                """)));

        service.provisionResources(conversationId, List.of(), modelConfig.getId());

        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().toString())
                .contains("search the web -> voyager://mcp/tavily/tavily_search")
                .contains("Do not propose those MCP capabilities again");
        ArgumentCaptor<WorkflowAiMessage> savedMessages =
                ArgumentCaptor.forClass(WorkflowAiMessage.class);
        verify(messageRepository, atLeastOnce()).saveAndFlush(savedMessages.capture());
        assertThat(savedMessages.getAllValues())
                .noneMatch(message -> message.getRole() == WorkflowAiMessageRole.USER);
    }

    @Test
    void provisionResourcesCreatesFunctionThenRegeneratesAsl() {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setStage(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());

        UUID functionId = UUID.randomUUID();
        when(functionRegistryService.createFunction(any())).thenReturn(
                new FunctionDefinitionResponseDTO(
                        functionId, "normalize-address", "Cleans an address", 1,
                        FunctionStatus.ENABLED, Instant.now(), Instant.now()));
        when(functionRegistryService.createVersion(eq(functionId), any())).thenReturn(
                new FunctionVersionResponseDTO(
                        UUID.randomUUID(), functionId, 1, FunctionSourceMode.SINGLE_FILE, 71,
                        true, false, "code", null, List.of(), null, null, 2.0, 10.0, 131072,
                        1024, 65536, false, "note", List.of(), FunctionVersionStatus.AVAILABLE,
                        Instant.now(), Instant.now()));
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"stage":"ASL_READY","message":"All set.",
                 "aslDefinition":{"StartAt":"Clean","States":{"Clean":{"Type":"Succeed"}}}}
                """)));

        WorkflowAiProposedFunctionDTO proposed = new WorkflowAiProposedFunctionDTO(
                "normalizeAddress", "Cleans an address", 71, "import sys", null, "needed");

        WorkflowAiResponseDTO response = service.provisionResources(
                conversationId, List.of(proposed), modelConfig.getId());

        verify(functionRegistryService).createFunction(
                argThat(request -> request.name().equals("normalize-address")));
        verify(functionRegistryService).createVersion(
                eq(functionId), argThat(version -> version.languageId() == 71));
        verify(functionRuntimePolicy).assertLanguageSupported(71);
        assertThat(response.stage()).isEqualTo(WorkflowAiConversationStage.ASL_READY);
        assertThat(response.aslDefinition()).isNotNull();
        assertThat(conversation.getResourcePlan()).isNull();
    }

    @Test
    void provisionResourcesKeepsAskingWhenMcpToolsStillMissing() {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        conversation.setStage(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());

        UUID functionId = UUID.randomUUID();
        when(functionRegistryService.createFunction(any())).thenReturn(
                new FunctionDefinitionResponseDTO(
                        functionId, "normalize-address", "Cleans an address", 1,
                        FunctionStatus.ENABLED, Instant.now(), Instant.now()));
        when(functionRegistryService.createVersion(eq(functionId), any())).thenReturn(
                new FunctionVersionResponseDTO(
                        UUID.randomUUID(), functionId, 1, FunctionSourceMode.SINGLE_FILE, 71,
                        true, false, "code", null, List.of(), null, null, 2.0, 10.0, 131072,
                        1024, 65536, false, "note", List.of(), FunctionVersionStatus.AVAILABLE,
                        Instant.now(), Instant.now()));
        // The function is created, but a Slack capability still has no attached MCP server, so the
        // model re-proposes only the unmet requirement and stays in the review stage.
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"stage":"RESOURCES_PROPOSED","message":"Attach a Slack MCP server, then continue.",
                 "resourcePlan":{"functions":[],"mcpRequirements":[{"capability":"send a Slack message",
                 "reason":"the final step posts to #alerts","trustLevelHint":"WRITE"}]}}
                """)));

        WorkflowAiProposedFunctionDTO proposed = new WorkflowAiProposedFunctionDTO(
                "normalize-address", "Cleans an address", 71, "import sys", null, "needed");

        WorkflowAiResponseDTO response = service.provisionResources(
                conversationId, List.of(proposed), modelConfig.getId());

        assertThat(response.stage())
                .isEqualTo(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        assertThat(response.aslDefinition()).isNull();
        assertThat(response.resourcePlan()).isNotNull();
        assertThat(response.resourcePlan().mcpRequirements()).hasSize(1);
        assertThat(response.resourcePlan().mcpRequirements().get(0).capability())
                .isEqualTo("send a Slack message");
    }

    @Test
    void parsesResourcePlanDespiteJsonCommentsAndUnescapedNewlines() {
        when(aiModelConfigService.resolveModel(modelConfig.getId()))
                .thenReturn(modelConfig);
        when(modelResolver.resolve(modelConfig)).thenReturn(chatLanguageModel);
        when(messageRepository.findByConversationOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        // Near-JSON a small model routinely emits: a // comment and a real newline inside sourceCode.
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from(
                "{\"stage\":\"RESOURCES_PROPOSED\",\"message\":\"Proposing a function.\","
                        + "\"resourcePlan\":{\"functions\":[{\"name\":\"shorten_and_title_case\","
                        + "\"description\":\"Shorten\",\"languageId\":71, // Python\n"
                        + "\"sourceCode\":\"line1\nline2\",\"rationale\":\"needed\"}],"
                        + "\"mcpRequirements\":[]}}")));

        WorkflowAiResponseDTO response = service.startConversation(
                "shorten a title", modelConfig.getId(), null, null);

        assertThat(response.stage())
                .isEqualTo(WorkflowAiConversationStage.RESOURCES_PROPOSED);
        assertThat(response.resourcePlan()).isNotNull();
        assertThat(response.resourcePlan().functions()).hasSize(1);
        assertThat(response.resourcePlan().functions().get(0).name())
                .isEqualTo("shorten-and-title-case");
    }

    private WorkflowAiConversation conversation(UUID conversationId) {
        WorkflowAiConversation conversation = new WorkflowAiConversation();
        conversation.setId(conversationId);
        conversation.setName("Daily digest");
        conversation.setInitialInstruction("send a daily digest");
        conversation.setModelConfig(modelConfig);
        conversation.setWorkspaceKind(WorkflowAiWorkspaceKind.AI_CHAT);
        conversation.setStage(WorkflowAiConversationStage.ASL_READY);
        return conversation;
    }

    private void stubGeneratedIds() {
        lenient().when(conversationRepository.save(any(WorkflowAiConversation.class)))
                .thenAnswer(invocation -> {
                    WorkflowAiConversation conversation = invocation.getArgument(0);
                    if (conversation.getId() == null) {
                        conversation.setId(UUID.randomUUID());
                    }
                    return conversation;
                });
        lenient().when(conversationRepository.saveAndFlush(any(WorkflowAiConversation.class)))
                .thenAnswer(invocation -> {
                    WorkflowAiConversation conversation = invocation.getArgument(0);
                    if (conversation.getId() == null) {
                        conversation.setId(UUID.randomUUID());
                    }
                    return conversation;
                });
        lenient().when(messageRepository.save(any(WorkflowAiMessage.class)))
                .thenAnswer(invocation -> {
                    WorkflowAiMessage message = invocation.getArgument(0);
                    if (message.getId() == null) {
                        message.setId(UUID.randomUUID());
                    }
                    return message;
                });
        lenient().when(messageRepository.saveAndFlush(any(WorkflowAiMessage.class)))
                .thenAnswer(invocation -> {
                    WorkflowAiMessage message = invocation.getArgument(0);
                    if (message.getId() == null) {
                        message.setId(UUID.randomUUID());
                    }
                    return message;
                });
    }
}
