package com.job.scheduler.service;

import com.job.scheduler.dto.EmbeddingRankingModelResultDTO;
import com.job.scheduler.dto.EmbeddingRankingResultDTO;
import com.job.scheduler.dto.EmbeddingRankingRunDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.EmbeddingRankingRun;
import com.job.scheduler.entity.ResourceEvalQuery;
import com.job.scheduler.enums.AiModelRole;
import com.job.scheduler.enums.EmbeddingRankingStatus;
import com.job.scheduler.repository.AiModelConfigRepository;
import com.job.scheduler.repository.EmbeddingRankingRunRepository;
import com.job.scheduler.repository.ResourceEvalQueryRepository;
import com.job.scheduler.service.CatalogResourceProvider.CatalogResource;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ranks registered EMBEDDING-role models by retrieval quality. Ground truth is synthetic: the
 * default chat model writes one natural-language query per catalog resource (cached in
 * {@link ResourceEvalQuery}), then each embedding model embeds the catalog and the queries
 * <em>in memory</em> — models differ in dimension, so this never touches the fixed-width
 * production {@code resource_embeddings} column — and is scored on where it ranks the correct
 * resource for each query (recall@1, recall@k, MRR) plus average embedding latency.
 *
 * <p>Runs asynchronously on a single background thread; callers start a run and poll the latest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingRankingService {
    private static final String QUERY_SYSTEM_PROMPT = """
            You write ONE short, natural request a user would type to accomplish a task that the
            given tool or function would satisfy. Describe the user's goal in plain language.
            Do NOT mention or copy the tool/function name or its wording. Output only the request:
            a single sentence, no quotes, no explanation.
            """;

    private final CatalogResourceProvider catalogResourceProvider;
    private final ResourceEvalQueryRepository queryRepository;
    private final EmbeddingRankingRunRepository runRepository;
    private final AiModelConfigService aiModelConfigService;
    private final AiModelConfigRepository modelConfigRepository;
    private final WorkflowAiModelResolver modelResolver;
    private final ObjectMapper objectMapper;

    @Value("${scheduler.workflow-ai.embedding.top-k:8}")
    private int topK;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "embedding-ranking");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Validates prerequisites, records a RUNNING run, and kicks off scoring in the background. If a
     * run is already in flight, returns it instead of starting a second.
     */
    @Transactional
    public EmbeddingRankingRunDTO start() {
        Optional<EmbeddingRankingRun> running = runRepository
                .findFirstByStatusOrderByStartedAtDesc(EmbeddingRankingStatus.RUNNING);
        if (running.isPresent()) {
            return toDto(running.get());
        }

        List<CatalogResource> resources = catalogResourceProvider.enabledResources();
        if (resources.size() < 2) {
            throw new IllegalStateException(
                    "Add at least 2 functions or MCP tools before ranking retrieval."
            );
        }
        if (embeddingModels().isEmpty()) {
            throw new IllegalStateException("Register at least one embedding model first.");
        }
        // A chat model is required to generate the ground-truth queries; fail fast if absent.
        if (modelConfigRepository
                .findFirstByEnabledTrueAndRoleOrderByDefaultModelDescDisplayNameAsc(AiModelRole.CHAT)
                .isEmpty()) {
            throw new IllegalStateException(
                    "Register an enabled chat model — it generates the evaluation queries."
            );
        }

        EmbeddingRankingRun run = new EmbeddingRankingRun();
        run.setStatus(EmbeddingRankingStatus.RUNNING);
        EmbeddingRankingRun saved = runRepository.save(run);
        UUID runId = saved.getId();
        executor.submit(() -> execute(runId));
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public EmbeddingRankingRunDTO latest() {
        return runRepository.findFirstByOrderByStartedAtDesc()
                .map(this::toDto)
                .orElse(null);
    }

    // -- Background run ----------------------------------------------------------------------

    private void execute(UUID runId) {
        try {
            ensureQueries();
            List<CatalogResource> resources = catalogResourceProvider.enabledResources();
            Map<String, String> queries = loadQueries(resources);
            if (queries.isEmpty()) {
                throw new IllegalStateException(
                        "Could not generate any evaluation queries (chat model unavailable?)."
                );
            }

            List<EmbeddingRankingModelResultDTO> results = new ArrayList<>();
            for (AiModelConfig model : embeddingModels()) {
                results.add(scoreModel(model, resources, queries));
            }
            // Successful models first, ranked by recall@k then MRR then latency; errored last.
            results.sort(Comparator
                    .comparing((EmbeddingRankingModelResultDTO r) -> r.error() != null)
                    .thenComparing(r -> orZero(r.recallAtK()), Comparator.reverseOrder())
                    .thenComparing(r -> orZero(r.mrr()), Comparator.reverseOrder())
                    .thenComparing(r -> orMax(r.avgLatencyMs())));

            EmbeddingRankingResultDTO result = new EmbeddingRankingResultDTO(
                    topK, resources.size(), queries.size(), Instant.now(), results
            );
            completeRun(runId, result);
        } catch (Exception exception) {
            log.warn("Embedding ranking run {} failed: {}", runId, exception.getMessage());
            failRun(runId, exception.getMessage());
        }
    }

    /** (Re)generates the synthetic query for any resource whose text changed or has none. */
    private void ensureQueries() {
        AiModelConfig chatConfig = aiModelConfigService.resolveModel(null);
        ChatModel chatModel = modelResolver.resolve(chatConfig);
        for (CatalogResource resource : catalogResourceProvider.enabledResources()) {
            String hash = hash(resource.text());
            Optional<ResourceEvalQuery> existing = queryRepository
                    .findByResourceTypeAndResourceId(resource.type(), resource.id());
            if (existing.isPresent() && hash.equals(existing.get().getSourceHash())) {
                continue;
            }
            String query = generateQuery(chatModel, resource);
            if (query == null || query.isBlank()) {
                continue;
            }
            ResourceEvalQuery record = existing.orElseGet(ResourceEvalQuery::new);
            record.setResourceType(resource.type());
            record.setResourceId(resource.id());
            record.setQueryText(query);
            record.setSourceHash(hash);
            record.setGeneratedByModel(chatConfig.getModelName());
            queryRepository.save(record);
        }
    }

    private String generateQuery(ChatModel chatModel, CatalogResource resource) {
        try {
            String text = chatModel.chat(List.of(
                    SystemMessage.systemMessage(QUERY_SYSTEM_PROMPT),
                    UserMessage.userMessage("Resource:\n" + resource.text() + "\n\nWrite the request:")
            )).aiMessage().text();
            return cleanQuery(text);
        } catch (RuntimeException exception) {
            log.warn("Eval-query generation failed for {}: {}", resource.name(), exception.getMessage());
            return null;
        }
    }

    /** Strips reasoning blocks, quotes, and extra lines from a generated query. */
    private String cleanQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw;
        int thinkEnd = text.lastIndexOf("</think>");
        if (thinkEnd >= 0) {
            text = text.substring(thinkEnd + "</think>".length());
        }
        text = text.trim();
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline).trim();
        }
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private EmbeddingRankingModelResultDTO scoreModel(
            AiModelConfig model,
            List<CatalogResource> resources,
            Map<String, String> queries
    ) {
        try {
            EmbeddingModel embeddingModel = modelResolver.resolveEmbeddingModel(model);
            long totalNanos = 0;
            int embedCount = 0;
            Integer dims = null;

            List<float[]> resourceVectors = new ArrayList<>(resources.size());
            for (CatalogResource resource : resources) {
                long start = System.nanoTime();
                float[] vector = embeddingModel.embed(resource.text()).content().vector();
                totalNanos += System.nanoTime() - start;
                embedCount++;
                if (dims == null) {
                    dims = vector.length;
                } else if (vector.length != dims) {
                    throw new IllegalStateException("Model returned inconsistent embedding dimensions");
                }
                resourceVectors.add(vector);
            }

            int k = Math.min(topK, resources.size());
            int scored = 0;
            double hitsAt1 = 0;
            double hitsAtK = 0;
            double reciprocalRankSum = 0;
            for (int i = 0; i < resources.size(); i++) {
                String query = queries.get(key(resources.get(i)));
                if (query == null || query.isBlank()) {
                    continue;
                }
                long start = System.nanoTime();
                float[] queryVector = embeddingModel.embed(query).content().vector();
                totalNanos += System.nanoTime() - start;
                embedCount++;

                int rank = rankOf(queryVector, resourceVectors, i);
                scored++;
                if (rank == 1) {
                    hitsAt1++;
                }
                if (rank <= k) {
                    hitsAtK++;
                }
                reciprocalRankSum += 1.0 / rank;
            }

            if (scored == 0) {
                return errorResult(model, "No evaluation queries available for the catalog.");
            }
            return new EmbeddingRankingModelResultDTO(
                    model.getId(),
                    model.getDisplayName(),
                    model.getModelName(),
                    dims,
                    hitsAt1 / scored,
                    hitsAtK / scored,
                    reciprocalRankSum / scored,
                    (totalNanos / 1_000_000.0) / embedCount,
                    scored,
                    null
            );
        } catch (RuntimeException exception) {
            return errorResult(model, exception.getMessage());
        }
    }

    private List<AiModelConfig> embeddingModels() {
        return modelConfigRepository
                .findByEnabledTrueAndRoleOrderByDefaultModelDescDisplayNameAsc(AiModelRole.EMBEDDING);
    }

    private Map<String, String> loadQueries(List<CatalogResource> resources) {
        Map<String, String> map = new HashMap<>();
        for (CatalogResource resource : resources) {
            queryRepository.findByResourceTypeAndResourceId(resource.type(), resource.id())
                    .ifPresent(query -> map.put(key(resource), query.getQueryText()));
        }
        return map;
    }

    private void completeRun(UUID runId, EmbeddingRankingResultDTO result) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(EmbeddingRankingStatus.COMPLETED);
            run.setResult(objectMapper.writeValueAsString(result));
            run.setFinishedAt(Instant.now());
            runRepository.save(run);
        });
    }

    private void failRun(UUID runId, String message) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(EmbeddingRankingStatus.FAILED);
            run.setError(message);
            run.setFinishedAt(Instant.now());
            runRepository.save(run);
        });
    }

    private EmbeddingRankingRunDTO toDto(EmbeddingRankingRun run) {
        EmbeddingRankingResultDTO result = null;
        if (run.getResult() != null) {
            try {
                result = objectMapper.readValue(run.getResult(), EmbeddingRankingResultDTO.class);
            } catch (RuntimeException exception) {
                log.warn("Could not parse ranking result for run {}: {}", run.getId(),
                        exception.getMessage());
            }
        }
        return new EmbeddingRankingRunDTO(
                run.getId(),
                run.getStatus(),
                result,
                run.getError(),
                run.getStartedAt(),
                run.getFinishedAt()
        );
    }

    private EmbeddingRankingModelResultDTO errorResult(AiModelConfig model, String message) {
        return new EmbeddingRankingModelResultDTO(
                model.getId(), model.getDisplayName(), model.getModelName(),
                null, null, null, null, null, 0, message
        );
    }

    /** 1-based rank of the target resource among all resources by cosine similarity to the query. */
    static int rankOf(float[] queryVector, List<float[]> resourceVectors, int targetIndex) {
        double targetSim = cosine(queryVector, resourceVectors.get(targetIndex));
        int rank = 1;
        for (int j = 0; j < resourceVectors.size(); j++) {
            if (j != targetIndex && cosine(queryVector, resourceVectors.get(j)) > targetSim) {
                rank++;
            }
        }
        return rank;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static String key(CatalogResource resource) {
        return resource.type() + ":" + resource.id();
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private static double orMax(Double value) {
        return value == null ? Double.MAX_VALUE : value;
    }

    private String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception exception) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
