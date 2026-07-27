package com.job.scheduler.service;

import com.job.scheduler.dto.AiModelEvaluationDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelEvaluationMode;
import com.job.scheduler.enums.AiModelEvaluationStatus;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.repository.AiModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelEvaluationServiceTest {
    @Mock
    private AiModelConfigRepository modelRepository;
    @Mock
    private AiModelConfigService modelConfigService;
    @Mock
    private WorkflowAiConversationService conversationService;

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

        when(modelConfigService.resolveModel(model.getId())).thenReturn(model);
        when(modelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(modelRepository.saveAndFlush(model)).thenReturn(model);

        Executor queuedExecutor = queuedEvaluation::set;
        service = new AiModelEvaluationService(
                modelRepository,
                modelConfigService,
                conversationService,
                new ObjectMapper(),
                queuedExecutor
        );
    }

    @Test
    void startPersistsProgressAndRejectsAConcurrentRun() {
        AiModelEvaluationDTO started = service.start(
                model.getId(),
                AiModelEvaluationMode.RELIABILITY
        );

        assertEquals(AiModelEvaluationStatus.RUNNING, started.status());
        assertEquals(3, started.repetitions());
        assertEquals(21, started.totalCases());
        assertEquals(0, started.completedCases());
        assertFalse(started.cancelRequested());
        assertTrue(queuedEvaluation.get() != null);

        assertThrows(
                IllegalStateException.class,
                () -> service.start(model.getId(), AiModelEvaluationMode.QUICK)
        );

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
                AiModelEvaluationMode.QUICK
        );

        AiModelEvaluationDTO cancellationRequested = service.cancel(
                model.getId(),
                started.runId()
        );
        assertTrue(cancellationRequested.cancelRequested());

        queuedEvaluation.get().run();

        AiModelEvaluationDTO cancelled = service.latest(model.getId());
        assertEquals(AiModelEvaluationStatus.CANCELLED, cancelled.status());
        assertEquals(0, cancelled.completedCases());
        assertFalse(cancelled.cancelRequested());
        assertTrue(cancelled.finishedAt() != null);
        verify(conversationService, never()).startConversation(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
