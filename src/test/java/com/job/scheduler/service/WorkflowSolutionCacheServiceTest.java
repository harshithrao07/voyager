package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.WorkflowSolutionEmbedding;
import com.job.scheduler.repository.WorkflowSolutionEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Gating and distance-band behaviour of the semantic response cache. */
@ExtendWith(MockitoExtension.class)
class WorkflowSolutionCacheServiceTest {

    @Mock private WorkflowSolutionEmbeddingRepository solutionRepository;
    @Mock private WorkflowAiEmbeddingService embeddingService;
    @Mock private AiModelConfigService aiModelConfigService;

    private WorkflowSolutionCacheService service;

    private static final float[] VECTOR = {0.1f, 0.2f, 0.3f};
    private static final String INSTRUCTION = "email me the weather every morning";
    private static final String ASL =
            "{\"StartAt\":\"S\",\"States\":{\"S\":{\"Type\":\"Succeed\"}}}";

    @BeforeEach
    void setUp() {
        service = new WorkflowSolutionCacheService(
                solutionRepository, embeddingService, aiModelConfigService
        );
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "adaptMaxDistance", 0.35);
    }

    @Test
    void findAdaptationReturnsEmptyWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThat(service.findAdaptation(INSTRUCTION)).isEmpty();
        verifyNoInteractions(embeddingService, solutionRepository);
    }

    @Test
    void findAdaptationReturnsEmptyWhenNearestIsBeyondTheBand() {
        when(embeddingService.embed(INSTRUCTION)).thenReturn(Optional.of(VECTOR));
        when(solutionRepository.findNearestWithDistance(any(), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(new Object[] {solution(), 0.5}));

        assertThat(service.findAdaptation(INSTRUCTION)).isEmpty();
    }

    @Test
    void findAdaptationReturnsPriorSolutionWithinTheBand() {
        when(embeddingService.embed(INSTRUCTION)).thenReturn(Optional.of(VECTOR));
        when(solutionRepository.findNearestWithDistance(any(), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(new Object[] {solution(), 0.12}));

        Optional<WorkflowSolutionCacheService.Adaptation> adaptation =
                service.findAdaptation(INSTRUCTION);

        assertThat(adaptation).isPresent();
        assertThat(adaptation.get().instruction()).isEqualTo(INSTRUCTION);
        assertThat(adaptation.get().asl()).isEqualTo(ASL);
        assertThat(adaptation.get().distance()).isEqualTo(0.12);
    }

    @Test
    void findAdaptationReturnsEmptyWhenInstructionCannotBeEmbedded() {
        when(embeddingService.embed(INSTRUCTION)).thenReturn(Optional.empty());

        assertThat(service.findAdaptation(INSTRUCTION)).isEmpty();
        verify(solutionRepository, never()).findNearestWithDistance(any(), any());
    }

    @Test
    void recordSolutionSkipsWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.recordSolution(UUID.randomUUID(), UUID.randomUUID(), INSTRUCTION, ASL);

        verifyNoInteractions(solutionRepository, embeddingService, aiModelConfigService);
    }

    @Test
    void recordSolutionWritesANewRow() {
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(aiModelConfigService.findDefaultEmbeddingModel())
                .thenReturn(Optional.of(embeddingModel("nomic-embed-text")));
        when(solutionRepository.findBySourceConversationId(conversationId))
                .thenReturn(Optional.empty());
        when(embeddingService.embed(INSTRUCTION)).thenReturn(Optional.of(VECTOR));

        service.recordSolution(conversationId, workflowId, INSTRUCTION, ASL);

        verify(solutionRepository).save(any(WorkflowSolutionEmbedding.class));
    }

    @Test
    void recordSolutionSkipsReEmbeddingWhenInstructionUnchanged() {
        UUID conversationId = UUID.randomUUID();
        AiModelConfig model = embeddingModel("nomic-embed-text");
        when(aiModelConfigService.findDefaultEmbeddingModel()).thenReturn(Optional.of(model));

        // Prime an existing row whose hash matches what the service will compute for this instruction.
        WorkflowSolutionEmbedding existing = solution();
        existing.setEmbedding(VECTOR);
        String hash = (String) ReflectionTestUtils.invokeMethod(
                service, "sourceHash", model.getModelName(), INSTRUCTION
        );
        existing.setSourceHash(hash);
        when(solutionRepository.findBySourceConversationId(conversationId))
                .thenReturn(Optional.of(existing));

        service.recordSolution(conversationId, UUID.randomUUID(), INSTRUCTION, ASL);

        verify(embeddingService, never()).embed(any());
        verify(solutionRepository).save(existing);
    }

    @Test
    void reconcileReEmbedsRowsFromAPreviousModel() {
        AiModelConfig current = embeddingModel("mxbai-embed-large:latest");
        when(aiModelConfigService.findDefaultEmbeddingModel()).thenReturn(Optional.of(current));

        WorkflowSolutionEmbedding stale = solution();
        stale.setEmbeddingModel("nomic-embed-text");
        stale.setEmbedding(new float[] {9f, 9f, 9f});
        when(solutionRepository.findByEmbeddingModelNot("mxbai-embed-large:latest"))
                .thenReturn(List.of(stale));
        when(embeddingService.embed(INSTRUCTION)).thenReturn(Optional.of(VECTOR));

        service.reconcile();

        assertThat(stale.getEmbeddingModel()).isEqualTo("mxbai-embed-large:latest");
        assertThat(stale.getEmbedding()).isEqualTo(VECTOR);
        verify(solutionRepository).save(stale);
    }

    @Test
    void reconcileDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.reconcile();

        verifyNoInteractions(solutionRepository, embeddingService, aiModelConfigService);
    }

    @Test
    void reconcileLeavesRowStaleWhenReEmbeddingFails() {
        when(aiModelConfigService.findDefaultEmbeddingModel())
                .thenReturn(Optional.of(embeddingModel("mxbai-embed-large:latest")));
        WorkflowSolutionEmbedding stale = solution();
        stale.setEmbeddingModel("nomic-embed-text");
        when(solutionRepository.findByEmbeddingModelNot("mxbai-embed-large:latest"))
                .thenReturn(List.of(stale));
        when(embeddingService.embed(INSTRUCTION)).thenReturn(Optional.empty());

        service.reconcile();

        assertThat(stale.getEmbeddingModel()).isEqualTo("nomic-embed-text");
        verify(solutionRepository, never()).save(any());
    }

    private WorkflowSolutionEmbedding solution() {
        WorkflowSolutionEmbedding embedding = new WorkflowSolutionEmbedding();
        embedding.setSourceConversationId(UUID.randomUUID());
        embedding.setInstructionText(INSTRUCTION);
        embedding.setWorkflowAsl(ASL);
        return embedding;
    }

    private AiModelConfig embeddingModel(String name) {
        AiModelConfig config = new AiModelConfig();
        config.setModelName(name);
        return config;
    }
}
