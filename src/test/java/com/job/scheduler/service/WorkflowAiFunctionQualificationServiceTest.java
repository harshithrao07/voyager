package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionRunResultDTO;
import com.job.scheduler.dto.FunctionVersionRequestDTO;
import com.job.scheduler.dto.FunctionVersionResponseDTO;
import com.job.scheduler.dto.WorkflowAiProposedFunctionDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.FunctionInvocationStatus;
import com.job.scheduler.enums.FunctionSourceMode;
import com.job.scheduler.enums.FunctionVersionStatus;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowAiFunctionQualificationServiceTest {

    @Mock
    private WorkflowAiModelResolver modelResolver;
    @Mock
    private FunctionInvocationService functionInvocationService;
    @Mock
    private FunctionRegistryService functionRegistryService;
    @Mock
    private ChatModel chatModel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowAiFunctionQualificationService service;
    private AiModelConfig modelConfig;

    @BeforeEach
    void setUp() {
        service = new WorkflowAiFunctionQualificationService(
                modelResolver,
                functionInvocationService,
                functionRegistryService,
                new WorkflowAiProposedFunctionSafetyValidator(objectMapper),
                objectMapper
        );
        modelConfig = new AiModelConfig();
        when(modelResolver.resolve(modelConfig)).thenReturn(chatModel);
    }

    @Test
    void savesTestsAndPublishesOnlyAfterJudge0MatchesExpectedOutput() {
        when(chatModel.chat(anyList())).thenReturn(modelResponse("""
                {"testCases":[{"name":"trims spaces","input":"\\"  Main St  \\"",
                "expectedOutput":"\\"Main St\\"","expectedError":null}]}
                """));
        when(functionInvocationService.run(any())).thenReturn(new FunctionRunResultDTO(
                FunctionInvocationStatus.SUCCEEDED,
                objectMapper.readTree("\"Main St\""),
                "\"Main St\"",
                null, null, null, 0, 3, "Accepted",
                null, null, 0.01, 1024L
        ));

        WorkflowAiFunctionQualificationService.QualificationResult result = service.qualify(
                proposal(),
                draft(),
                modelConfig,
                "normalize-address"
        );

        assertThat(result.qualified()).isTrue();
        assertThat(result.resourceUri()).isEqualTo("voyager://function/normalize-address@v1");
        ArgumentCaptor<FunctionVersionRequestDTO> metadata =
                ArgumentCaptor.forClass(FunctionVersionRequestDTO.class);
        verify(functionRegistryService).updateVersionMetadata(
                any(), org.mockito.ArgumentMatchers.eq(1), metadata.capture()
        );
        assertThat(metadata.getValue().testCases())
                .singleElement()
                .satisfies(test -> assertThat(test.name()).isEqualTo("trims spaces"));
        verify(functionRegistryService).publishVersion(any(), org.mockito.ArgumentMatchers.eq(1));
    }

    @Test
    void leavesDraftWithoutTestsWhenJudge0CannotCompileCode() {
        when(chatModel.chat(anyList())).thenReturn(modelResponse("""
                {"testCases":[{"name":"normal input","input":"{}",
                "expectedOutput":"{}","expectedError":null}]}
                """));
        when(functionInvocationService.run(any())).thenReturn(new FunctionRunResultDTO(
                FunctionInvocationStatus.FAILED,
                null, null, null, "SyntaxError", null, 1, 6, "Compilation Error",
                "States.FunctionCompileError", "Function compilation failed", null, null
        ));

        WorkflowAiFunctionQualificationService.QualificationResult result = service.qualify(
                proposal(),
                draft(),
                modelConfig,
                "normalize-address"
        );

        assertThat(result.qualified()).isFalse();
        assertThat(result.reason()).contains("Function compilation failed");
        verify(functionRegistryService, never()).updateVersionMetadata(any(), anyInt(), any());
        verify(functionRegistryService, never()).publishVersion(any(), anyInt());
    }

    private WorkflowAiProposedFunctionDTO proposal() {
        return new WorkflowAiProposedFunctionDTO(
                "normalize-address",
                "Trim surrounding spaces from an address string.",
                71,
                "import json,sys\nprint(json.dumps(json.load(sys.stdin).strip()))",
                null,
                "Local deterministic transformation"
        );
    }

    private FunctionVersionResponseDTO draft() {
        UUID functionId = UUID.randomUUID();
        return new FunctionVersionResponseDTO(
                UUID.randomUUID(), functionId, 1, FunctionSourceMode.SINGLE_FILE, 71,
                true, false, proposal().sourceCode(), null, List.of(), null, null,
                2.0, 10.0, 131072, 1024, 65536, false,
                "Generated", List.of(), FunctionVersionStatus.DRAFT, Instant.now(), Instant.now()
        );
    }

    private ChatResponse modelResponse(String json) {
        return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
    }
}
