package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowGenerationResponseDTO;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowGenerationServiceTest {

    @Mock
    private ObjectProvider<ChatModel> chatModelProvider;
    @Mock
    private ChatModel chatModel;
    @Mock
    private WorkflowAiResourceCatalogService resourceCatalogService;
    @Mock
    private AslDefinitionValidator validator;
    private ObjectMapper objectMapper;

    private WorkflowGenerationService service;

    @Captor
    private ArgumentCaptor<List<ChatMessage>> messagesCaptor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new WorkflowGenerationService(chatModelProvider, resourceCatalogService, validator, objectMapper);
    }

    @Test
    void generatesValidWorkflowOnFirstAttempt() throws Exception {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        
        when(resourceCatalogService.buildMcpToolsDocumentation())
                .thenReturn("- voyager://mcp/github/list_prs [trust: READ_ONLY] — Lists PRs");
        when(resourceCatalogService.buildFunctionsDocumentation())
                .thenReturn("- voyager://function/calculate-tax@v3 — Computes tax for an order");

        String validJson = "{\"Type\": \"Task\", \"Resource\": \"voyager://system/webhook\", \"End\": true}";
        JsonNode parsed = objectMapper.readTree(validJson);
        
        when(chatModel.chat(anyList())).thenReturn(aiResponse(AiMessage.from("```json\n" + validJson + "\n```")));
        when(validator.validate(parsed)).thenReturn(new AslValidationResult(List.of()));

        WorkflowGenerationResponseDTO response = service.generateWorkflow("do something");
        
        assertThat(response.getDefinition()).isEqualTo(parsed);
        assertThat(response.getValidationIssues()).isEmpty();
        
        verify(chatModel, times(1)).chat(messagesCaptor.capture());
        List<ChatMessage> messages = messagesCaptor.getValue();
        assertThat(messages).hasSize(2); // System, User
        assertThat(messageText(messages.get(0))).contains("voyager://mcp/github/list_prs");
        assertThat(messageText(messages.get(0))).contains("voyager://function/calculate-tax@v3");
        assertThat(messageText(messages.get(0))).contains("headers(obj<string,string> optional)");
        assertThat(messageText(messages.get(0))).contains("GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS");
        assertThat(messageText(messages.get(0))).contains("Computes tax for an order");
        assertThat(messageText(messages.get(1))).contains("do something");
    }

    @Test
    void retriesOnValidationErrorAndSucceeds() throws Exception {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(resourceCatalogService.buildMcpToolsDocumentation()).thenReturn("None registered.");
        when(resourceCatalogService.buildFunctionsDocumentation()).thenReturn("None registered.");

        String invalidJson = "{\"Type\": \"Task\"}"; // Missing End
        JsonNode parsedInvalid = objectMapper.readTree(invalidJson);
        String validJson = "{\"Type\": \"Task\", \"End\": true}";
        JsonNode parsedValid = objectMapper.readTree(validJson);

        when(chatModel.chat(anyList()))
                .thenReturn(aiResponse(AiMessage.from(invalidJson)))
                .thenReturn(aiResponse(AiMessage.from(validJson)));

        AslValidationIssue issue = new AslValidationIssue(
                "$.Type",
                com.job.scheduler.workflow.asl.validation.AslValidationCategory.ASL,
                "MISSING_TRANSITION",
                "Missing next transition"
        );
        when(validator.validate(parsedInvalid)).thenReturn(new AslValidationResult(List.of(issue)));
        when(validator.validate(parsedValid)).thenReturn(new AslValidationResult(List.of()));

        WorkflowGenerationResponseDTO response = service.generateWorkflow("test");
        
        assertThat(response.getDefinition()).isEqualTo(parsedValid);
        assertThat(response.getValidationIssues()).isEmpty();
        verify(chatModel, times(2)).chat(anyList());
    }

    /** langchain4j 1.x replaced Response<AiMessage> with ChatResponse, which has no from(). */
    private static ChatResponse aiResponse(AiMessage message) {
        return ChatResponse.builder().aiMessage(message).build();
    }

    /** 1.x dropped ChatMessage.text(); the concrete message types expose their own accessors. */
    private static String messageText(ChatMessage message) {
        return message instanceof SystemMessage system
                ? system.text()
                : ((UserMessage) message).singleText();
    }
}
