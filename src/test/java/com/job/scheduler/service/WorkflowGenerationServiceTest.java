package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowGenerationResponseDTO;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
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
    private ObjectProvider<ChatLanguageModel> chatLanguageModelProvider;
    @Mock
    private ChatLanguageModel chatLanguageModel;
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
        service = new WorkflowGenerationService(chatLanguageModelProvider, resourceCatalogService, validator, objectMapper);
    }

    @Test
    void generatesValidWorkflowOnFirstAttempt() throws Exception {
        when(chatLanguageModelProvider.getIfAvailable()).thenReturn(chatLanguageModel);
        
        when(resourceCatalogService.buildMcpToolsDocumentation())
                .thenReturn("- voyager://mcp/github/list_prs [trust: READ_ONLY] — Lists PRs");
        when(resourceCatalogService.buildFunctionsDocumentation())
                .thenReturn("- voyager://function/calculate-tax@v3 — Computes tax for an order");

        String validJson = "{\"Type\": \"Task\", \"Resource\": \"voyager://system/webhook\", \"End\": true}";
        JsonNode parsed = objectMapper.readTree(validJson);
        
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("```json\n" + validJson + "\n```")));
        when(validator.validate(parsed)).thenReturn(new AslValidationResult(List.of()));

        WorkflowGenerationResponseDTO response = service.generateWorkflow("do something");
        
        assertThat(response.getDefinition()).isEqualTo(parsed);
        assertThat(response.getValidationIssues()).isEmpty();
        
        verify(chatLanguageModel, times(1)).generate(messagesCaptor.capture());
        List<ChatMessage> messages = messagesCaptor.getValue();
        assertThat(messages).hasSize(2); // System, User
        assertThat(messages.get(0).text()).contains("voyager://mcp/github/list_prs");
        assertThat(messages.get(0).text()).contains("voyager://function/calculate-tax@v3");
        assertThat(messages.get(0).text()).contains("headers(obj<string,string> optional)");
        assertThat(messages.get(0).text()).contains("GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS");
        assertThat(messages.get(0).text()).contains("Computes tax for an order");
        assertThat(messages.get(1).text()).contains("do something");
    }

    @Test
    void retriesOnValidationErrorAndSucceeds() throws Exception {
        when(chatLanguageModelProvider.getIfAvailable()).thenReturn(chatLanguageModel);
        when(resourceCatalogService.buildMcpToolsDocumentation()).thenReturn("None registered.");
        when(resourceCatalogService.buildFunctionsDocumentation()).thenReturn("None registered.");

        String invalidJson = "{\"Type\": \"Task\"}"; // Missing End
        JsonNode parsedInvalid = objectMapper.readTree(invalidJson);
        String validJson = "{\"Type\": \"Task\", \"End\": true}";
        JsonNode parsedValid = objectMapper.readTree(validJson);

        when(chatLanguageModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from(invalidJson)))
                .thenReturn(Response.from(AiMessage.from(validJson)));

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
        verify(chatLanguageModel, times(2)).generate(anyList());
    }
}
