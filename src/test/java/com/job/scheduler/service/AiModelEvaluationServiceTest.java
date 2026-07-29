package com.job.scheduler.service;

import com.job.scheduler.dto.AiModelEvaluationDTO;
import com.job.scheduler.dto.WorkflowAiMessageDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.AiModelEvaluationRun;
import com.job.scheduler.enums.AiModelEvaluationMode;
import com.job.scheduler.enums.AiModelEvaluationStatus;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.WorkflowAiConversationStage;
import com.job.scheduler.enums.WorkflowAiMessageRole;
import com.job.scheduler.repository.AiModelConfigRepository;
import com.job.scheduler.repository.AiModelEvaluationRunRepository;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelEvaluationServiceTest {
    @Mock
    private AiModelConfigRepository modelRepository;
    @Mock
    private AiModelEvaluationRunRepository runRepository;
    @Mock
    private AiModelConfigService modelConfigService;
    @Mock
    private WorkflowAiConversationService conversationService;
    @Mock
    private AiModelEvaluationJudgeService judgeService;
    @Mock
    private ChatModel judgeChatModel;

    private final AtomicReference<Runnable> queuedEvaluation = new AtomicReference<>();
    private AiModelConfig model;
    private AiModelEvaluationService service;

    @BeforeEach
    void setUp() {
        model = new AiModelConfig();
        model.setId(UUID.randomUUID());
        model.setDisplayName("Local model");
        model.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_LOCAL);
        model.setBaseUrl("http://localhost:11434/v1");
        model.setModelName("qwen2.5:7b");
        model.setEnabled(true);

        lenient().when(modelConfigService.resolveModel(model.getId())).thenReturn(model);
        when(modelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        // The bad-judge test never reaches a save; strict stubbing would flag this as unused there.
        lenient().when(modelRepository.saveAndFlush(model)).thenReturn(model);

        Executor queuedExecutor = queuedEvaluation::set;
        service = new AiModelEvaluationService(
                modelRepository,
                runRepository,
                modelConfigService,
                conversationService,
                judgeService,
                new ObjectMapper(),
                queuedExecutor
        );
    }

    private WorkflowAiResponseDTO chatOnlyResponse() {
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
                "{\"stage\":\"COLLECTING_WORKFLOW_DETAILS\",\"message\":\"All good.\"}"
        );
    }

    private WorkflowAiResponseDTO chatOnlyResponseWithAssistant(UUID messageId) {
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
                new WorkflowAiMessageDTO(
                        messageId,
                        WorkflowAiMessageRole.ASSISTANT,
                        "All good.",
                        model.getId(),
                        model.getDisplayName(),
                        10L,
                        null,
                        null,
                        null,
                        null,
                        "STOP",
                        null,
                        null,
                        Instant.now()
                ),
                "{\"stage\":\"COLLECTING_WORKFLOW_DETAILS\",\"message\":\"All good.\"}"
        );
    }

    @Test
    void startPersistsProgressAndRejectsAConcurrentRun() {
        AiModelEvaluationDTO started = service.start(
                model.getId(),
                AiModelEvaluationMode.RELIABILITY,
                null
        );

        assertEquals(AiModelEvaluationStatus.RUNNING, started.status());
        assertEquals(3, started.repetitions());
        assertEquals(21, started.totalCases());
        assertEquals(0, started.completedCases());
        assertFalse(started.cancelRequested());
        assertTrue(queuedEvaluation.get() != null);
        ArgumentCaptor<AiModelEvaluationRun> runCaptor =
                ArgumentCaptor.forClass(AiModelEvaluationRun.class);
        verify(runRepository).saveAndFlush(runCaptor.capture());
        assertEquals(started.runId(), runCaptor.getValue().getId());
        assertEquals(AiModelEvaluationStatus.RUNNING, runCaptor.getValue().getStatus());

        assertThrows(
                IllegalStateException.class,
                () -> service.start(model.getId(), AiModelEvaluationMode.QUICK, null)
        );

        // Staleness applies to a finished result; while RUNNING the stored payload is live progress.
        model.setEvaluationStatus(AiModelEvaluationStatus.COMPLETED);
        when(conversationService.promptFingerprint()).thenReturn("sha256:current");
        model.setEvaluationResult("{\"promptFingerprint\":\"sha256:old\"}");
        assertTrue(service.latest(model.getId()).stale());
        model.setEvaluationResult("{\"promptFingerprint\":\"sha256:current\"}");
        assertFalse(service.latest(model.getId()).stale());
    }

    @Test
    void cancellationIsPersistedAndStopsBeforeCallingTheModel() {
        AiModelEvaluationDTO started = service.start(
                model.getId(),
                AiModelEvaluationMode.QUICK,
                null
        );

        AiModelEvaluationDTO cancellationRequested = service.cancel(
                model.getId(),
                started.runId()
        );
        assertEquals(AiModelEvaluationStatus.CANCELLED, cancellationRequested.status());
        assertFalse(cancellationRequested.cancelRequested());
        assertTrue(cancellationRequested.finishedAt() != null);

        queuedEvaluation.get().run();

        AiModelEvaluationDTO cancelled = service.latest(model.getId());
        assertEquals(AiModelEvaluationStatus.CANCELLED, cancelled.status());
        assertEquals(0, cancelled.completedCases());
        assertFalse(cancelled.cancelRequested());
        assertTrue(cancelled.finishedAt() != null);
        verify(conversationService, never()).startEvaluationConversation(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void runWithoutAJudgeStaysDeterministicOnly() {
        when(conversationService.startEvaluationConversation(
                anyString(), any(), anyString(), any()
        ))
                .thenAnswer(invocation -> chatOnlyResponse());

        service.start(model.getId(), AiModelEvaluationMode.QUICK, null);
        queuedEvaluation.get().run();

        AiModelEvaluationDTO completed = service.latest(model.getId());
        assertEquals(AiModelEvaluationStatus.COMPLETED, completed.status());
        assertFalse(completed.result().has("judge"));
        verify(judgeService, never()).judge(any(), any(), any(), any(), anyList(), anyInt());
        ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(conversationService, times(7)).startEvaluationConversation(
                anyString(), any(), anyString(), timeoutCaptor.capture()
        );
        assertEquals(Duration.ofSeconds(120), timeoutCaptor.getAllValues().get(0));
        assertEquals(Duration.ofSeconds(45), timeoutCaptor.getAllValues().get(1));
        assertEquals(Duration.ofSeconds(45), timeoutCaptor.getAllValues().get(6));
    }

    @Test
    void returnsPersistedRunHistoryNewestFirst() {
        AiModelEvaluationRun run = new AiModelEvaluationRun();
        run.setId(UUID.randomUUID());
        run.setModelConfigId(model.getId());
        run.setModelDisplayName(model.getDisplayName());
        run.setStatus(AiModelEvaluationStatus.COMPLETED);
        run.setMode(AiModelEvaluationMode.QUICK);
        run.setRepetitions(1);
        run.setCompletedCases(7);
        run.setTotalCases(7);
        run.setResult("""
                {"promptFingerprint":"sha256:test","summary":{"recommendation":"RECOMMENDED"}}
                """);
        run.setStartedAt(Instant.parse("2026-07-28T12:00:00Z"));
        run.setFinishedAt(Instant.parse("2026-07-28T12:01:00Z"));
        when(conversationService.promptFingerprint()).thenReturn("sha256:test");
        when(runRepository.findByModelConfigIdOrderByStartedAtDesc(
                model.getId(),
                PageRequest.of(0, 10)
        )).thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 10), 1));

        var history = service.history(model.getId(), 0, 10);

        assertEquals(1, history.totalElements());
        assertEquals(1, history.runs().size());
        assertEquals(run.getId(), history.runs().get(0).runId());
        assertEquals(7, history.runs().get(0).completedCases());
        assertFalse(history.runs().get(0).stale());
    }

    @Test
    void retryTimeoutOnlyFailsSupersessionAndUsesEvaluationLimit() {
        UUID messageId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        when(conversationService.startEvaluationConversation(
                anyString(), any(), anyString(), any()
        )).thenAnswer(invocation -> calls.incrementAndGet() == 7
                ? chatOnlyResponseWithAssistant(messageId)
                : chatOnlyResponse());
        when(conversationService.regenerateEvaluationMessage(
                eq(messageId),
                eq(model.getId()),
                eq(Duration.ofSeconds(45))
        )).thenThrow(new IllegalStateException("request timed out"));

        service.start(model.getId(), AiModelEvaluationMode.QUICK, null);
        queuedEvaluation.get().run();

        JsonNode retryObservation = service.latest(model.getId())
                .result()
                .path("observations")
                .get(6);
        assertTrue(retryObservation.path("metrics").path("validation_clean").path("passed").asBoolean());
        assertTrue(retryObservation.path("metrics").path("response_contract").path("passed").asBoolean());
        assertTrue(retryObservation.path("metrics").path("general_chat_mode").path("passed").asBoolean());
        assertFalse(retryObservation.path("metrics").path("retry_supersession").path("passed").asBoolean());
        assertEquals(
                "Retry model/provider did not respond within the 45s evaluation limit.",
                retryObservation.path("metrics").path("retry_supersession").path("detail").asText()
        );
    }

    @Test
    void judgedRunAggregatesAdvisoryVerdictsWithoutMovingGates() {
        AiModelConfig judgeConfig = new AiModelConfig();
        judgeConfig.setId(UUID.randomUUID());
        judgeConfig.setDisplayName("Judge model");
        judgeConfig.setModelName("qwen3:32b");
        judgeConfig.setProviderType(AiModelProviderType.OPENAI_COMPATIBLE_LOCAL);
        judgeConfig.setEnabled(true);
        when(modelConfigService.resolveModel(judgeConfig.getId())).thenReturn(judgeConfig);
        when(judgeService.resolve(judgeConfig)).thenReturn(judgeChatModel);
        when(conversationService.startEvaluationConversation(
                anyString(), any(), anyString(), any()
        ))
                .thenAnswer(invocation -> chatOnlyResponse());
        when(judgeService.judge(
                eq(judgeChatModel),
                eq(judgeConfig),
                any(JsonNode.class),
                any(WorkflowAiResponseDTO.class),
                anyList(),
                eq(AiModelEvaluationJudgeService.DEFAULT_PASS_SCORE)
        )).thenAnswer(invocation -> {
            JsonNode testCase = invocation.getArgument(2);
            return switch (testCase.path("id").asText()) {
                case "asl-succeed" -> AiModelEvaluationJudgeService.Judgment
                        .scored(2, false, "Wrong state shape.", 5);
                case "mcp-weather" -> AiModelEvaluationJudgeService.Judgment
                        .error("judge endpoint down", 3);
                default -> AiModelEvaluationJudgeService.Judgment
                        .scored(5, true, "Meets the expectation.", 4);
            };
        });

        service.start(model.getId(), AiModelEvaluationMode.QUICK, judgeConfig.getId());
        queuedEvaluation.get().run();

        AiModelEvaluationDTO completed = service.latest(model.getId());
        assertEquals(AiModelEvaluationStatus.COMPLETED, completed.status());
        JsonNode judge = completed.result().path("judge");
        assertEquals("Judge model", judge.path("displayName").asText());
        assertEquals(4, judge.path("passScore").asInt());
        assertEquals(7, judge.path("judgedCases").asInt());
        assertEquals(6, judge.path("scoredCases").asInt());
        assertEquals(1, judge.path("erroredCases").asInt());
        assertEquals(4.5, judge.path("meanScore").asDouble());
        assertEquals(0.8333, judge.path("passRate").asDouble(), 0.0001);
        assertEquals("STRONG", judge.path("verdict").asText());
        assertEquals(1, judge.path("failures").size());
        assertTrue(judge.path("failures").get(0).asText().startsWith("asl-succeed:"));
        assertEquals(1, judge.path("errors").size());
        assertTrue(judge.path("errors").get(0).asText().startsWith("mcp-weather:"));

        // Advisory only: judge verdicts appear on observations but leave metrics untouched.
        JsonNode firstObservation = completed.result().path("observations").get(0);
        assertTrue(firstObservation.has("judge"));
        // Each observation carries the exact prompt it was run against so the UI can show it per case.
        assertEquals("Hi buddy, how are you?", firstObservation.path("instruction").asText());
        // The raw model reply is captured so a failure can be diagnosed from the UI.
        assertEquals(
                "{\"stage\":\"COLLECTING_WORKFLOW_DETAILS\",\"message\":\"All good.\"}",
                firstObservation.path("response").path("rawModelReply").asText()
        );
        assertFalse(completed.result().path("qualityGates").has("llm_judge"));
    }

    @Test
    void rejectsAJudgeThatDoesNotResolveBeforeMarkingTheRunAsStarted() {
        UUID badJudgeId = UUID.randomUUID();
        when(modelConfigService.resolveModel(badJudgeId))
                .thenThrow(new IllegalArgumentException("AI model config does not exist"));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.start(model.getId(), AiModelEvaluationMode.QUICK, badJudgeId)
        );
        assertTrue(model.getEvaluationRunId() == null);
    }
}
