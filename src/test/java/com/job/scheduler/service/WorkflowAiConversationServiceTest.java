package com.job.scheduler.service;

import com.job.scheduler.dto.CreateWorkflowRequestDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.dto.WorkflowResponseDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.WorkflowAiConversation;
import com.job.scheduler.entity.WorkflowAiMessage;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.WorkflowAiConversationStage;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.repository.WorkflowAiConversationRepository;
import com.job.scheduler.repository.WorkflowAiMessageRepository;
import com.job.scheduler.workflow.asl.runtime.AslRuntimeCapabilityValidator;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationCategory;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
                "2026-06-28T09:00:00+05:30"
        );

        assertThat(response.conversationId()).isNotNull();
        assertThat(response.stage()).isEqualTo(WorkflowAiConversationStage.ASL_READY);
        assertThat(response.aslDefinition()).isEqualTo(definition);
        assertThat(response.validationIssues()).isEmpty();
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
    }
}
