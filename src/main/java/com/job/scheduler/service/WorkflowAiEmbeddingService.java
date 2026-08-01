package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.ResourceEmbedding;
import com.job.scheduler.enums.ResourceEmbeddingType;
import com.job.scheduler.repository.ResourceEmbeddingRepository;
import com.job.scheduler.service.CatalogResourceProvider.CatalogResource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Embeds catalog resources (functions + MCP tools) with the default EMBEDDING-role model and
 * serves nearest-neighbour retrieval for {@link WorkflowAiResourceCatalogService}. Every path is
 * failure-safe: a missing/unreachable embedding model never blocks resource creation or catalog
 * building — callers simply fall back to the full-detail catalog.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAiEmbeddingService {
    private final ResourceEmbeddingRepository embeddingRepository;
    private final CatalogResourceProvider catalogResourceProvider;
    private final AiModelConfigService aiModelConfigService;
    private final WorkflowAiModelResolver modelResolver;

    @Value("${scheduler.workflow-ai.embedding.enabled:true}")
    private boolean enabled;

    @Value("${scheduler.workflow-ai.embedding.dimensions:768}")
    private int dimensions;

    @Value("${scheduler.workflow-ai.embedding.min-catalog-size:12}")
    private int minCatalogSize;

    @Value("${scheduler.workflow-ai.embedding.top-k:8}")
    private int topK;

    /**
     * Whether retrieval-based catalog tiering should apply for a catalog of this size. Below the
     * threshold the full catalog is cheap enough that retrieval adds only recall risk, so callers
     * send every resource at full detail (the pre-RAG behaviour).
     */
    public boolean retrievalActive(int catalogSize) {
        return enabled
                && catalogSize >= minCatalogSize
                && aiModelConfigService.findDefaultEmbeddingModel().isPresent();
    }

    /**
     * Nearest resource ids of a type for the given intent, or an empty set when embeddings are
     * disabled, no embedding model is registered, or the intent can't be embedded. An empty set
     * signals the caller to keep those resources in the cheap index tier rather than dropping them.
     */
    public Set<String> selectRelevantResourceIds(ResourceEmbeddingType type, String intent) {
        if (!enabled || intent == null || intent.isBlank()) {
            return Set.of();
        }
        Optional<float[]> query = embed(intent);
        if (query.isEmpty()) {
            return Set.of();
        }
        try {
            List<UUID> ids = embeddingRepository.findNearestResourceIds(
                    type, query.get(), PageRequest.of(0, Math.max(1, topK))
            );
            Set<String> result = new HashSet<>();
            ids.forEach(id -> result.add(id.toString()));
            return result;
        } catch (RuntimeException exception) {
            log.warn("Nearest-resource query failed for {}: {}", type, exception.getMessage());
            return Set.of();
        }
    }

    /** Embeds text with the default embedding model. Empty on any failure (never throws). */
    public Optional<float[]> embed(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return Optional.empty();
        }
        Optional<AiModelConfig> model = aiModelConfigService.findDefaultEmbeddingModel();
        if (model.isEmpty()) {
            return Optional.empty();
        }
        try {
            EmbeddingModel embeddingModel = modelResolver.resolveEmbeddingModel(model.get());
            Response<Embedding> response = embeddingModel.embed(text);
            float[] vector = response.content().vector();
            if (vector.length != dimensions) {
                log.warn("Embedding model {} returned {} dims, expected {}; skipping",
                        model.get().getModelName(), vector.length, dimensions);
                return Optional.empty();
            }
            return Optional.of(vector);
        } catch (RuntimeException exception) {
            log.warn("Embedding request failed via model {}: {}",
                    model.get().getModelName(), exception.getMessage());
            return Optional.empty();
        }
    }

    // -- Reconciliation ---------------------------------------------------------------------

    /**
     * Fills missing/stale embeddings for every enabled function and MCP tool and drops embeddings
     * for resources that are no longer enabled. Runs on a fixed delay; also safe to call directly
     * after a function is published or the MCP tool catalog is synced.
     */
    @Scheduled(
            fixedDelayString = "${scheduler.workflow-ai.embedding.reconcile-delay-ms:900000}",
            initialDelayString = "${scheduler.workflow-ai.embedding.reconcile-initial-delay-ms:60000}"
    )
    @Transactional
    public void reconcile() {
        if (!enabled || aiModelConfigService.findDefaultEmbeddingModel().isEmpty()) {
            return;
        }
        String modelName = aiModelConfigService.findDefaultEmbeddingModel()
                .map(AiModelConfig::getModelName)
                .orElse(null);
        if (modelName == null) {
            return;
        }
        Map<ResourceEmbeddingType, Set<UUID>> live = new EnumMap<>(ResourceEmbeddingType.class);
        for (CatalogResource resource : catalogResourceProvider.enabledResources()) {
            upsertIfStale(resource.type(), resource.id(), resource.text(), modelName);
            live.computeIfAbsent(resource.type(), key -> new HashSet<>()).add(resource.id());
        }
        for (ResourceEmbeddingType type : ResourceEmbeddingType.values()) {
            pruneOrphans(type, live.getOrDefault(type, Set.of()));
        }
    }

    private void pruneOrphans(ResourceEmbeddingType type, Set<UUID> live) {
        for (UUID embedded : embeddingRepository.findResourceIdsByType(type)) {
            if (!live.contains(embedded)) {
                embeddingRepository.deleteByResourceTypeAndResourceId(type, embedded);
            }
        }
    }

    /** Re-embeds only when the resource's text or the embedding model changed. */
    private void upsertIfStale(
            ResourceEmbeddingType type,
            UUID resourceId,
            String sourceText,
            String modelName
    ) {
        if (sourceText == null || sourceText.isBlank()) {
            return;
        }
        String hash = sourceHash(modelName, sourceText);
        if (embeddingRepository.findSourceHash(type, resourceId)
                .filter(hash::equals)
                .isPresent()) {
            return;
        }
        Optional<float[]> vector = embed(sourceText);
        if (vector.isEmpty()) {
            return;
        }
        ResourceEmbedding embedding = embeddingRepository
                .findByResourceTypeAndResourceId(type, resourceId)
                .orElseGet(ResourceEmbedding::new);
        embedding.setResourceType(type);
        embedding.setResourceId(resourceId);
        embedding.setEmbedding(vector.get());
        embedding.setEmbeddingModel(modelName);
        embedding.setDimensions(dimensions);
        embedding.setSourceHash(hash);
        embedding.setEmbeddedAt(Instant.now());
        embeddingRepository.save(embedding);
    }

    private String sourceHash(String modelName, String sourceText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(modelName.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] bytes = digest.digest(sourceText.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is guaranteed present; fall back to a non-cryptographic identity.
            return Integer.toHexString((modelName + '\0' + sourceText).hashCode());
        }
    }
}
