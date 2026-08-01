package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowAiExplanationResponseDTO;
import com.job.scheduler.dto.WorkflowPreActivationReviewResponseDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowAiAuthoringServiceTest {

    @Mock AiModelConfigService aiModelConfigService;
    @Mock WorkflowAiModelResolver modelResolver;
    @Mock AslDefinitionValidator validator;
    @Mock ChatModel chatModel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowAiAuthoringService service;
    private JsonNode definition;

    @BeforeEach
    void setUp() throws Exception {
        service = new WorkflowAiAuthoringService(
                aiModelConfigService, modelResolver, validator, objectMapper);
        definition = objectMapper.readTree("""
                {"StartAt":"Call","States":{
                  "Call":{"Type":"Task","Resource":"voyager://function/a","Next":"Done"},
                  "Done":{"Type":"Succeed"}}}
                """);
        lenient().when(aiModelConfigService.resolveModel(null)).thenReturn(new AiModelConfig());
        lenient().when(modelResolver.resolve(any())).thenReturn(chatModel);
        lenient().when(validator.validate(definition)).thenReturn(new AslValidationResult(List.of()));
    }

    @Test
    void explainsWorkflowWithoutEditingIt() {
        reply("""
                {"summary":"Calls a function, then succeeds.",
                 "stateDetails":["Call: invokes function a","Done: completes the workflow"]}
                """);

        WorkflowAiExplanationResponseDTO response = service.explain(definition, null);

        assertThat(response.summary()).contains("Calls a function");
        assertThat(response.stateDetails()).hasSize(2);
    }

    @Test
    void acceptsPlainTextExplanation() {
        reply("<think>reasoning</think>\nThe workflow calls function a and then succeeds.");

        WorkflowAiExplanationResponseDTO response = service.explain(definition, null);

        assertThat(response.summary()).isEqualTo(
                "The workflow calls function a and then succeeds.");
    }

    @Test
    void returnsWarningOnlyPreActivationReview() {
        reply("""
                {"warnings":[{
                  "category":"ERROR_HANDLING",
                  "title":"Task failure is unhandled",
                  "detail":"Call can fail without a Catch path, which can terminate the run.",
                  "stateName":"Call",
                  "solution":"Add a Catch block"
                }]}
                """);

        WorkflowPreActivationReviewResponseDTO response =
                service.reviewBeforeActivation(definition, null);

        assertThat(response.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.category()).isEqualTo("ERROR_HANDLING");
            assertThat(warning.title()).isEqualTo("Task failure is unhandled");
            assertThat(warning.detail()).contains("terminate the run");
            assertThat(warning.stateName()).isEqualTo("Call");
        });
    }

    @Test
    void acceptsPreActivationReviewWithNoWarnings() {
        reply("{\"warnings\":[]}");

        WorkflowPreActivationReviewResponseDTO response =
                service.reviewBeforeActivation(definition, null);

        assertThat(response.warnings()).isEmpty();
    }

    private void reply(String text) {
        when(chatModel.chat(anyList())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
    }
}
