package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowExecutionDetailDTO;
import com.job.scheduler.dto.WorkflowExecutionScopeDTO;
import com.job.scheduler.dto.WorkflowExecutionSummaryDTO;
import com.job.scheduler.dto.WorkflowRunSummaryResponseDTO;
import com.job.scheduler.dto.WorkflowStateExecutionDTO;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowAiRunSummaryServiceTest {

    @Mock private WorkflowExecutionRepository executionRepository;
    @Mock private WorkflowExecutionInspectionService inspectionService;
    @Mock private AiModelConfigService aiModelConfigService;
    @Mock private WorkflowAiModelResolver modelResolver;
    @Mock private ChatModel chatModel;

    private WorkflowAiRunSummaryService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WorkflowAiRunSummaryService(
                executionRepository,
                inspectionService,
                aiModelConfigService,
                modelResolver,
                objectMapper
        );
        lenient().when(aiModelConfigService.resolveModel(null)).thenReturn(new AiModelConfig());
        lenient().when(modelResolver.resolve(any())).thenReturn(chatModel);
    }

    @Test
    void summarizesEveryPersistedStateUsingTheDefaultChatModel() {
        stubExecution(WorkflowExecutionStatus.SUCCEEDED);
        stubDetail();
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("""
                {
                  "headline":"Order processed successfully.",
                  "overview":"The workflow validated and processed one order.",
                  "outcome":"The execution succeeded with a processed result.",
                  "states":[
                    {"scopePath":"$","sequenceNumber":1,"summary":"Validated the order fields."},
                    {"scopePath":"$","sequenceNumber":2,"summary":"Processed the valid order."}
                  ]
                }
                """)).build());

        WorkflowRunSummaryResponseDTO result = service.summarize(workflowId, executionId);

        assertThat(result.headline()).isEqualTo("Order processed successfully.");
        assertThat(result.states()).hasSize(2);
        assertThat(result.states().get(0).stateName()).isEqualTo("ValidateOrder");
        assertThat(result.states().get(0).status()).isEqualTo(StateExecutionStatus.SUCCEEDED);
        assertThat(result.states().get(1).summary()).isEqualTo("Processed the valid order.");
        verify(aiModelConfigService).resolveModel(null);
    }

    @Test
    void ignoresInventedStatesAndFallsBackForMissingStateSummary() {
        stubExecution(WorkflowExecutionStatus.FAILED);
        stubDetail();
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("""
                {"headline":"Run failed.","overview":"Processing stopped.","outcome":"A task failed.",
                 "states":[{"scopePath":"$","sequenceNumber":99,"summary":"Invented state."}]}
                """)).build());

        WorkflowRunSummaryResponseDTO result = service.summarize(workflowId, executionId);

        assertThat(result.states()).extracting(state -> state.sequenceNumber()).containsExactly(1L, 2L);
        assertThat(result.states()).noneMatch(state -> state.summary().contains("Invented"));
        assertThat(result.states().get(0).summary()).contains("SUCCEEDED");
    }

    @Test
    void rejectsAnExecutionThatIsStillRunning() {
        stubExecution(WorkflowExecutionStatus.RUNNING);

        assertThatThrownBy(() -> service.summarize(workflowId, executionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only completed executions");
    }

    @Test
    void returnsDeterministicFallbackWhenModelReplyIsNotJson() {
        stubExecution(WorkflowExecutionStatus.SUCCEEDED);
        stubDetail();
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("not structured output"))
                .build());

        WorkflowRunSummaryResponseDTO result = service.summarize(workflowId, executionId);

        assertThat(result.headline()).contains("Run 7").contains("SUCCEEDED");
        assertThat(result.overview()).contains("2 state transitions");
        assertThat(result.states()).hasSize(2);
    }

    private void stubExecution(WorkflowExecutionStatus status) {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        Workflow workflow = mock(Workflow.class);
        WorkflowDefinition definition = mock(WorkflowDefinition.class);
        when(workflow.getId()).thenReturn(workflowId);
        when(execution.getWorkflow()).thenReturn(workflow);
        when(execution.getStatus()).thenReturn(status);
        lenient().when(execution.getWorkflowDefinition()).thenReturn(definition);
        lenient().when(definition.getDefinition()).thenReturn(
                "{\"StartAt\":\"ValidateOrder\",\"States\":{\"ValidateOrder\":{\"Type\":\"Pass\",\"Next\":\"ProcessOrder\"},\"ProcessOrder\":{\"Type\":\"Task\",\"Resource\":\"voyager://function/process-order\",\"End\":true}}}");
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
    }

    private void stubDetail() {
        var input = objectMapper.createObjectNode().put("orderId", "A-1");
        var output = objectMapper.createObjectNode().put("status", "processed");
        WorkflowExecutionSummaryDTO execution = new WorkflowExecutionSummaryDTO(
                executionId, workflowId, UUID.randomUUID(), 2, 7,
                WorkflowExecutionStatus.SUCCEEDED, null, input, output,
                null, null, null, Instant.now(), Instant.now(), Instant.now(), Instant.now()
        );
        WorkflowStateExecutionDTO validate = new WorkflowStateExecutionDTO(
                UUID.randomUUID(), 1, "ValidateOrder", AslStateType.PASS,
                StateExecutionStatus.SUCCEEDED, null, input, input, null,
                null, null, null, null, null, null, List.of()
        );
        WorkflowStateExecutionDTO process = new WorkflowStateExecutionDTO(
                UUID.randomUUID(), 2, "ProcessOrder", AslStateType.TASK,
                StateExecutionStatus.SUCCEEDED, "voyager://function/process-order",
                input, output, null, null, null, null, null, null, null, List.of()
        );
        WorkflowExecutionScopeDTO scope = new WorkflowExecutionScopeDTO(
                UUID.randomUUID(), null, null, "$", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(validate, process)
        );
        when(inspectionService.getExecution(workflowId, executionId))
                .thenReturn(new WorkflowExecutionDetailDTO(execution, List.of(scope)));
    }
}
