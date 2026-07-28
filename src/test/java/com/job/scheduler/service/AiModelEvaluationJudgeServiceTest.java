package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.WorkflowAiConversationStage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelEvaluationJudgeServiceTest {
    private static final int PASS_SCORE = AiModelEvaluationJudgeService.DEFAULT_PASS_SCORE;

    @Mock
    private WorkflowAiModelResolver modelResolver;
    @Mock
    private ChatModel judgeChatModel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiModelEvaluationJudgeService service;
    private AiModelConfig judgeConfig;
    private JsonNode testCase;

    @BeforeEach
    void setUp() {
        service = new AiModelEvaluationJudgeService(modelResolver, objectMapper);
        judgeConfig = new AiModelConfig();
        judgeConfig.setId(UUID.randomUUID());
        judgeConfig.setDisplayName("Judge model");
        judgeConfig.setModelName("qwen3:32b");
        lenient().when(modelResolver.supportsJsonMode(judgeConfig)).thenReturn(true);
        testCase = objectMapper.readTree("""
                {
                  "id": "asl-succeed",
                  "category": "asl",
                  "instruction": "Create a workflow with exactly one Succeed state named Done.",
                  "judge": {"expectation": "Exactly one Succeed state named Done."}
                }
                """);
    }

    private WorkflowAiResponseDTO chatResponse() {
        return new WorkflowAiResponseDTO(
                UUID.randomUUID(),
                "Benchmark case",
                WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS,
                "All good.",
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private void stubReplies(String... replies) {
        var stub = when(judgeChatModel.chat(any(ChatRequest.class)));
        for (String reply : replies) {
            stub = stub.thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from(reply)).build()
            );
        }
    }

    @Test
    void parsesACleanVerdict() {
        stubReplies("{\"score\": 5, \"rationale\": \"Exactly the requested state.\"}");

        AiModelEvaluationJudgeService.Judgment judgment = service.judge(
                judgeChatModel, judgeConfig, testCase, chatResponse(), PASS_SCORE
        );

        assertTrue(judgment.scoredSuccessfully());
        assertEquals(5, judgment.score());
        assertEquals(Boolean.TRUE, judgment.passed());
        assertEquals("Exactly the requested state.", judgment.rationale());
        assertNull(judgment.error());
        verify(judgeChatModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void survivesThinkingBlocksProseAndNearJson() {
        stubReplies("""
                <think>Let me weigh this carefully.</think>
                Sure, here is my verdict:
                {'score': '3/5', 'rationale': 'Partially satisfies the case',}
                """);

        AiModelEvaluationJudgeService.Judgment judgment = service.judge(
                judgeChatModel, judgeConfig, testCase, chatResponse(), PASS_SCORE
        );

        assertEquals(3, judgment.score());
        assertEquals(Boolean.FALSE, judgment.passed());
    }

    @Test
    void clampsAnOutOfRangeScore() {
        stubReplies("{\"score\": 9, \"rationale\": \"Overenthusiastic.\"}");

        AiModelEvaluationJudgeService.Judgment judgment = service.judge(
                judgeChatModel, judgeConfig, testCase, chatResponse(), PASS_SCORE
        );

        assertEquals(5, judgment.score());
    }

    @Test
    void retriesOnceWhenTheFirstReplyIsUnparseable() {
        stubReplies(
                "I would rate this response quite favorably overall.",
                "{\"score\": 4, \"rationale\": \"Meets the expectation.\"}"
        );

        AiModelEvaluationJudgeService.Judgment judgment = service.judge(
                judgeChatModel, judgeConfig, testCase, chatResponse(), PASS_SCORE
        );

        assertEquals(4, judgment.score());
        verify(judgeChatModel, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void reportsAnErrorInsteadOfThrowingWhenNoVerdictParses() {
        stubReplies("no json here", "still no json");

        AiModelEvaluationJudgeService.Judgment judgment = service.judge(
                judgeChatModel, judgeConfig, testCase, chatResponse(), PASS_SCORE
        );

        assertFalse(judgment.scoredSuccessfully());
        assertNull(judgment.score());
        assertTrue(judgment.error().contains("not a parseable verdict"));
    }

    @Test
    void reportsAnErrorWhenTheProviderThrows() {
        when(judgeChatModel.chat(any(ChatRequest.class)))
                .thenThrow(new IllegalStateException("connection refused"));

        AiModelEvaluationJudgeService.Judgment judgment = service.judge(
                judgeChatModel, judgeConfig, testCase, chatResponse(), PASS_SCORE
        );

        assertFalse(judgment.scoredSuccessfully());
        assertEquals("connection refused", judgment.error());
    }

    @Test
    void fallsBackToPlainPromptWhenJsonObjectModeIsRejected() {
        when(judgeChatModel.chat(any(ChatRequest.class)))
                .thenThrow(new IllegalStateException("response_format is not supported"))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"score\": 4, \"rationale\": \"Fine.\"}"))
                        .build());

        AiModelEvaluationJudgeService.Judgment judgment = service.judge(
                judgeChatModel, judgeConfig, testCase, chatResponse(), PASS_SCORE
        );

        assertEquals(4, judgment.score());
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(judgeChatModel, times(2)).chat(requests.capture());
        assertEquals(ResponseFormat.JSON, requests.getAllValues().get(0).responseFormat());
        assertNull(requests.getAllValues().get(1).responseFormat());
    }

    @Test
    void expectationFallsBackWhenTheSuiteHasNone() {
        JsonNode bare = objectMapper.readTree("{\"id\":\"x\",\"category\":\"asl\"}");
        assertEquals(
                "Exactly one Succeed state named Done.",
                AiModelEvaluationJudgeService.expectation(testCase)
        );
        assertTrue(AiModelEvaluationJudgeService.expectation(bare)
                .contains("satisfy the instruction"));
    }
}
