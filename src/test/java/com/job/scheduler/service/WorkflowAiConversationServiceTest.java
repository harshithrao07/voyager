package com.job.scheduler.service;

import com.job.scheduler.dto.CreateWorkflowRequestDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.dto.WorkflowResponseDTO;
import com.job.scheduler.dto.WorkflowAiWorkspaceRequestDTO;
import com.job.scheduler.dto.WorkflowAiWorkspaceSettingsDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.WorkflowAiConversation;
import com.job.scheduler.entity.WorkflowAiMessage;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.WorkflowAiConversationStage;
import com.job.scheduler.enums.WorkflowAiMessageRole;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.repository.WorkflowAiConversationRepository;
import com.job.scheduler.repository.WorkflowAiMessageRepository;
import com.job.scheduler.workflow.asl.runtime.AslRuntimeCapabilityValidator;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
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
    private WorkflowService workflowService;
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
                workflowService,
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
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));

        service.saveWorkspace(
                conversationId,
                new WorkflowAiWorkspaceRequestDTO(definition, canvasLayout, settings)
        );

        assertThat(objectMapper.readTree(conversation.getDraftAsl())).isEqualTo(definition);
        assertThat(objectMapper.readTree(conversation.getCanvasLayout())).isEqualTo(canvasLayout);
        assertThat(objectMapper.readTree(conversation.getWorkspaceSettings()).path("name").stringValue())
                .isEqualTo("Saved chat workflow");
        verify(conversationRepository).saveAndFlush(conversation);

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtAsc(conversation))
                .thenReturn(List.of());
        var restored = service.getConversation(conversationId);

        assertThat(restored.aslDefinition()).isEqualTo(definition);
        assertThat(restored.canvasLayout()).isEqualTo(canvasLayout);
        assertThat(restored.workspaceSettings()).isEqualTo(settings);
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
        conversation.setCanvasLayout(objectMapper.writeValueAsString(canvasLayout));
        conversation.setWorkspaceSettings(objectMapper.writeValueAsString(settings));
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));

        service.saveWorkspace(
                conversationId,
                new WorkflowAiWorkspaceRequestDTO(definition, canvasLayout, settings)
        );

        verify(conversationRepository, never()).saveAndFlush(any());
    }

    @Test
    void invalidWorkspaceDefinitionCannotReplaceLastValidAsl() throws Exception {
        UUID conversationId = UUID.randomUUID();
        WorkflowAiConversation conversation = conversation(conversationId);
        String validAsl = """
                {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}""";
        conversation.setDraftAsl(validAsl);
        JsonNode invalidDefinition = objectMapper.readTree("""
                {"StartAt":"Broken","States":{"Broken":{"Type":"Pass","Result":{},"End":true}}}
                """);
        JsonNode canvasLayout = objectMapper.readTree("{} ");
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

        assertThatThrownBy(() -> service.saveWorkspace(
                conversationId,
                new WorkflowAiWorkspaceRequestDTO(invalidDefinition, canvasLayout, settings)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot save an invalid JSONata ASL definition")
                .hasMessageContaining("JSONPATH_FIELD_NOT_SUPPORTED");

        assertThat(conversation.getDraftAsl()).isEqualTo(validAsl);
        verify(conversationRepository, never()).saveAndFlush(any());
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
    void rejectedModelAslNeverReplacesAuthoritativeDraft() throws Exception {
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
        verify(chatLanguageModel, times(2)).generate(anyList());
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
        ArgumentCaptor<CreateWorkflowRequestDTO> requestCaptor =
                ArgumentCaptor.forClass(CreateWorkflowRequestDTO.class);
        verify(workflowService).createWorkflow(requestCaptor.capture());
        assertThat(requestCaptor.getValue().definition().path("StartAt").stringValue())
                .isEqualTo("Done");
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

    private WorkflowAiConversation conversation(UUID conversationId) {
        WorkflowAiConversation conversation = new WorkflowAiConversation();
        conversation.setId(conversationId);
        conversation.setName("Daily digest");
        conversation.setInitialInstruction("send a daily digest");
        conversation.setModelConfig(modelConfig);
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
