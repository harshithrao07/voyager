package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowExecutionDetailDTO;
import com.job.scheduler.dto.WorkflowExecutionScopeDTO;
import com.job.scheduler.dto.WorkflowStateExecutionDTO;
import com.job.scheduler.dto.WorkflowTriageResponseDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.AslStateType;
import com.job.scheduler.enums.StateExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowAiFailureTriageServiceTest {

    private static final String ORIGINAL_ASL =
            "{\"StartAt\":\"Call\",\"States\":{\"Call\":{\"Type\":\"Task\",\"Resource\":\"voyager://function/f\",\"End\":true}}}";

    @Mock private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock private WorkflowExecutionInspectionService inspectionService;
    @Mock private AiModelConfigService aiModelConfigService;
    @Mock private WorkflowAiModelResolver modelResolver;
    @Mock private ChatModel chatModel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowAiFailureTriageService service;

    private final UUID workflowId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WorkflowAiFailureTriageService(
                workflowExecutionRepository,
                inspectionService,
                aiModelConfigService,
                modelResolver,
                objectMapper
        );
        lenient().when(aiModelConfigService.resolveModel(null)).thenReturn(new AiModelConfig());
        lenient().when(modelResolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(chatModel);
    }

    private WorkflowExecution failedExecution(WorkflowExecutionStatus status) {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        Workflow workflow = mock(Workflow.class);
        WorkflowDefinition definition = mock(WorkflowDefinition.class);
        lenient().when(workflow.getId()).thenReturn(workflowId);
        lenient().when(execution.getWorkflow()).thenReturn(workflow);
        lenient().when(execution.getStatus()).thenReturn(status);
        lenient().when(execution.getWorkflowDefinition()).thenReturn(definition);
        lenient().when(definition.getDefinition()).thenReturn(ORIGINAL_ASL);
        lenient().when(execution.getError()).thenReturn("Task failed");
        lenient().when(execution.getCause()).thenReturn("TASK_FAILED");
        lenient().when(execution.getInput()).thenReturn("{\"a\":1}");
        when(workflowExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        return execution;
    }

    private void stubFailingState() {
        WorkflowStateExecutionDTO state = new WorkflowStateExecutionDTO(
                UUID.randomUUID(), 1, "Call", AslStateType.TASK, StateExecutionStatus.FAILED,
                "voyager://function/f", null, null, null, "boom", "TASK_FAILED",
                null, null, null, null, List.of());
        WorkflowExecutionScopeDTO scope = new WorkflowExecutionScopeDTO(
                UUID.randomUUID(), null, null, "$", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, List.of(state));
        when(inspectionService.getExecution(workflowId, executionId))
                .thenReturn(new WorkflowExecutionDetailDTO(null, List.of(scope)));
    }

    private void stubModelReply(String text) {
        when(chatModel.chat(anyList()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
    }

    @Test
    void rejectsExecutionThatDidNotFail() {
        failedExecution(WorkflowExecutionStatus.SUCCEEDED);

        assertThatThrownBy(() -> service.triage(workflowId, executionId, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed or timed-out");
    }

    @Test
    void returnsRootCauseWithoutProposingAChange() {
        failedExecution(WorkflowExecutionStatus.FAILED);
        stubFailingState();
        stubModelReply("""
                {"rootCause":"The Task resource returned TASK_FAILED.",
                 "explanation":"The persisted Call state contains the error boom and the execution ended as FAILED."}
                """);

        WorkflowTriageResponseDTO result = service.triage(workflowId, executionId, null);

        assertThat(result.failingStateName()).isEqualTo("Call");
        assertThat(result.rootCause()).isEqualTo("The Task resource returned TASK_FAILED.");
        assertThat(result.explanation()).contains("persisted Call state");
        assertThat(result.patch().hasPatch()).isFalse();
    }

    @Test
    void usesTheRequestedDiagnosisModel() {
        UUID selectedModelId = UUID.randomUUID();
        failedExecution(WorkflowExecutionStatus.FAILED);
        stubFailingState();
        when(aiModelConfigService.resolveModel(selectedModelId)).thenReturn(new AiModelConfig());
        stubModelReply("""
                {"rootCause":"The state input was empty.",
                 "explanation":"The recorded input did not contain the value required by Call."}
                """);

        service.triage(workflowId, executionId, selectedModelId);

        verify(aiModelConfigService).resolveModel(selectedModelId);
    }

    @Test
    void ignoresPatchFieldsIfModelViolatesDiagnosisOnlyContract() {
        failedExecution(WorkflowExecutionStatus.FAILED);
        stubFailingState();
        stubModelReply("""
                {"rootCause":"The state input was missing data.",
                 "explanation":"The recorded state input was empty.",
                 "changes":["Change the workflow"],
                 "aslDefinition":%s}
                """.formatted(ORIGINAL_ASL));

        WorkflowTriageResponseDTO result = service.triage(workflowId, executionId, null);

        assertThat(result.rootCause()).isEqualTo("The state input was missing data.");
        assertThat(result.patch().hasPatch()).isFalse();
        assertThat(result.patch().aslDefinition()).isNull();
    }

    @Test
    void returnsDiagnosisForTimedOutExecution() {
        failedExecution(WorkflowExecutionStatus.TIMED_OUT);
        stubFailingState();
        stubModelReply("{\"rootCause\":\"External outage\",\"explanation\":\"The Task timed out while the external service was unavailable.\"}");

        WorkflowTriageResponseDTO result = service.triage(workflowId, executionId, null);

        assertThat(result.rootCause()).isEqualTo("External outage");
        assertThat(result.patch().hasPatch()).isFalse();
    }

    @Test
    void toleratesModelReplyWithProseAndThinkBlock() {
        failedExecution(WorkflowExecutionStatus.FAILED);
        stubFailingState();
        stubModelReply("<think>let me reason</think>\nDiagnosis:\n```json\n"
                + "{\"rootCause\":\"missing input\",\"explanation\":\"The recorded state input was empty.\"}\n```");

        WorkflowTriageResponseDTO result = service.triage(workflowId, executionId, null);

        assertThat(result.rootCause()).isEqualTo("missing input");
        assertThat(result.patch().hasPatch()).isFalse();
    }

    @Test
    void unparseableReplyStillReturnsFallbackDiagnosis() {
        failedExecution(WorkflowExecutionStatus.FAILED);
        stubFailingState();
        stubModelReply("the model rambled without any JSON");

        WorkflowTriageResponseDTO result = service.triage(workflowId, executionId, null);

        assertThat(result.rootCause()).isNotBlank();
        assertThat(result.patch().hasPatch()).isFalse();
    }

    @Test
    void notFoundWhenExecutionMissing() {
        when(workflowExecutionRepository.findById(executionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triage(workflowId, executionId, null))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }
}
