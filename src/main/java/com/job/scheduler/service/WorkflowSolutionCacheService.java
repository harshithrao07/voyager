package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.WorkflowSolutionEmbedding;
import com.job.scheduler.repository.WorkflowSolutionEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Semantic response cache for workflow generation. When a validated workflow is saved its
 * instruction is embedded and stored; when a new conversation starts, the nearest prior solution is
 * retrieved and — if close enough — injected into the prompt as an adaptation template so the model
 * amends a known-good workflow instead of designing cold.
 *
 * <p>Every path is failure-safe, exactly like {@link WorkflowAiEmbeddingService}: a missing or
 * unreachable embedding model never blocks saving a workflow or starting a conversation. The cache
 * only ever seeds the prompt — the model still regenerates and the result is validated against the
 * live catalog — so a stale template can never be served as a broken workflow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowSolutionCacheService {
    private final WorkflowSolutionEmbeddingRepository solutionRepository;
    private final WorkflowAiEmbeddingService embeddingService;
    private final AiModelConfigService aiModelConfigService;

    @Value("${scheduler.workflow-ai.solution-cache.enabled:false}")
    private boolean enabled;

    /**
     * Cosine-distance ceiling for treating a prior solution as a usable adaptation template. Below
     * this, the past workflow is similar enough to be worth adapting; above it, generate cold. Tuned
     * conservatively — the embedding model's real distance distribution should refine this once
     * there is data.
     */
    @Value("${scheduler.workflow-ai.solution-cache.adapt-max-distance:0.35}")
    private double adaptMaxDistance;

    /** A prior solution nearest to an instruction, with how far it sat in cosine distance. */
    public record Adaptation(String instruction, String asl, double distance) {}

    /**
     * Nearest cached solution to adapt for {@code instruction}, or empty when caching is disabled, no
     * embedding model is registered, the cache is empty, the instruction can't be embedded, or the
     * nearest solution is beyond the adaptation distance band.
     */
    public Optional<Adaptation> findAdaptation(String instruction) {
        if (!enabled || instruction == null || instruction.isBlank()) {
            return Optional.empty();
        }
        Optional<float[]> query = embeddingService.embed(instruction);
        if (query.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<Object[]> nearest = solutionRepository.findNearestWithDistance(
                    query.get(), PageRequest.of(0, 1)
            );
            if (nearest.isEmpty()) {
                return Optional.empty();
            }
            Object[] row = nearest.get(0);
            WorkflowSolutionEmbedding solution = (WorkflowSolutionEmbedding) row[0];
            double distance = ((Number) row[1]).doubleValue();
            if (distance > adaptMaxDistance) {
                return Optional.empty();
            }
            return Optional.of(new Adaptation(
                    solution.getInstructionText(),
                    solution.getWorkflowAsl(),
                    distance
            ));
        } catch (RuntimeException exception) {
            log.warn("Solution-cache lookup failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    /** Prompt block that presents a prior solution as an adaptation template (never authoritative). */
    public String adaptationPromptContext(Adaptation adaptation) {
        return """
                A previously validated Voyager workflow solved a similar request. Use it as a starting \
                template: adapt it to the new instruction, keep the parts that still apply, and change \
                what differs. Do not copy it blindly — verify every Resource URI against the current \
                catalog and drop anything the new request does not need.
                Similar past instruction: %s
                Its validated ASL definition:
                %s""".formatted(adaptation.instruction(), adaptation.asl());
    }

    /**
     * Records a validated workflow solution so future similar instructions can adapt it. Keyed on the
     * conversation, so re-saving updates the existing row; an unchanged instruction+model skips
     * re-embedding. Never throws — a cache write must not fail a workflow save.
     */
    @Transactional
    public void recordSolution(
            UUID conversationId,
            UUID workflowId,
            String instruction,
            String aslJson
    ) {
        if (!enabled || conversationId == null
                || instruction == null || instruction.isBlank()
                || aslJson == null || aslJson.isBlank()) {
            return;
        }
        Optional<AiModelConfig> model = aiModelConfigService.findDefaultEmbeddingModel();
        if (model.isEmpty()) {
            return;
        }
        String modelName = model.get().getModelName();
        String hash = sourceHash(modelName, instruction);

        Optional<WorkflowSolutionEmbedding> existing =
                solutionRepository.findBySourceConversationId(conversationId);
        boolean instructionUnchanged = existing
                .map(WorkflowSolutionEmbedding::getSourceHash)
                .filter(hash::equals)
                .isPresent();

        try {
            WorkflowSolutionEmbedding solution = existing.orElseGet(WorkflowSolutionEmbedding::new);
            solution.setSourceConversationId(conversationId);
            solution.setSourceWorkflowId(workflowId);
            solution.setInstructionText(instruction);
            solution.setWorkflowAsl(aslJson);

            if (!instructionUnchanged || solution.getEmbedding() == null) {
                Optional<float[]> vector = embeddingService.embed(instruction);
                if (vector.isEmpty()) {
                    return;
                }
                solution.setEmbedding(vector.get());
                solution.setEmbeddingModel(modelName);
                solution.setDimensions(WorkflowSolutionEmbedding.DIMENSIONS);
                solution.setSourceHash(hash);
                solution.setEmbeddedAt(Instant.now());
            }
            solutionRepository.save(solution);
        } catch (RuntimeException exception) {
            log.warn("Solution-cache write failed for conversation {}: {}",
                    conversationId, exception.getMessage());
        }
    }

    /**
     * Re-embeds cached solutions left over from a previous EMBEDDING model. Because each row keeps its
     * original instruction text, a model switch re-embeds in place with the new model rather than
     * discarding the cache — so cached solutions survive the switch and stay comparable to new queries
     * (embeddings from different models are not comparable). Runs on a fixed delay; failure-safe, so a
     * row that can't be re-embedded this pass is simply retried next pass. This is the self-healing
     * that lets the EMBEDDING model change with no manual cache cleanup.
     */
    @Scheduled(
            fixedDelayString = "${scheduler.workflow-ai.solution-cache.reconcile-delay-ms:900000}",
            initialDelayString = "${scheduler.workflow-ai.solution-cache.reconcile-initial-delay-ms:60000}"
    )
    @Transactional
    public void reconcile() {
        if (!enabled) {
            return;
        }
        Optional<AiModelConfig> model = aiModelConfigService.findDefaultEmbeddingModel();
        if (model.isEmpty()) {
            return;
        }
        String modelName = model.get().getModelName();
        List<WorkflowSolutionEmbedding> stale = solutionRepository.findByEmbeddingModelNot(modelName);
        for (WorkflowSolutionEmbedding row : stale) {
            Optional<float[]> vector = embeddingService.embed(row.getInstructionText());
            if (vector.isEmpty()) {
                continue; // leave stale; retried next pass
            }
            row.setEmbedding(vector.get());
            row.setEmbeddingModel(modelName);
            row.setDimensions(WorkflowSolutionEmbedding.DIMENSIONS);
            row.setSourceHash(sourceHash(modelName, row.getInstructionText()));
            row.setEmbeddedAt(Instant.now());
            solutionRepository.save(row);
        }
        if (!stale.isEmpty()) {
            log.info("Solution-cache reconcile re-embedded {} rows under model {}",
                    stale.size(), modelName);
        }
    }

    private String sourceHash(String modelName, String instruction) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(modelName.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] bytes = digest.digest(instruction.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            return Integer.toHexString((modelName + '\0' + instruction).hashCode());
        }
    }
}
