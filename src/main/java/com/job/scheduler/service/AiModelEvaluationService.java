package com.job.scheduler.service;

import com.job.scheduler.dto.AiModelEvaluationDTO;
import com.job.scheduler.dto.WorkflowAiMcpRequirementDTO;
import com.job.scheduler.dto.WorkflowAiProposedFunctionDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelEvaluationMode;
import com.job.scheduler.enums.AiModelEvaluationStatus;
import com.job.scheduler.enums.WorkflowAiConversationStage;
import com.job.scheduler.repository.AiModelConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AiModelEvaluationService {
    private static final String SUITE_RESOURCE = "ai-evals/workflow-ai-v1.json";
    private static final Pattern CHAT_DEFLECTION = Pattern.compile(
            "(?i)\\b(workflow name|name (?:for|of) (?:the|your) workflow|what workflow|"
                    + "build(?:ing)? your workflow|create (?:a|the) workflow)\\b"
    );
    private static final Pattern UNSAFE_FUNCTION_SOURCE = Pattern.compile(
            "YOUR[_\\s-]*(?:API[_\\s-]*KEY|TOKEN|SECRET|PASSWORD)|REPLACE[_\\s-]*ME"
                    + "|<\\s*(?:API[_\\s-]*KEY|TOKEN|SECRET|PASSWORD)\\s*>"
                    + "|\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}"
                    + "|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
                    + "|\\b(?:sk-|gh[pousr]_)[A-Za-z0-9_-]{16,}",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> FORBIDDEN_ASL_FIELDS = Set.of(
            "InputPath", "OutputPath", "Parameters", "Result", "ResultPath",
            "ResultSelector", "ItemsPath"
    );

    private final AiModelConfigRepository modelRepository;
    private final AiModelConfigService modelConfigService;
    private final WorkflowAiConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final JsonNode suite;

    public AiModelEvaluationService(
            AiModelConfigRepository modelRepository,
            AiModelConfigService modelConfigService,
            WorkflowAiConversationService conversationService,
            ObjectMapper objectMapper,
            @Qualifier("aiModelEvaluationExecutor") Executor executor
    ) {
        this.modelRepository = modelRepository;
        this.modelConfigService = modelConfigService;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.suite = loadSuite(objectMapper);
    }

    public synchronized AiModelEvaluationDTO start(
            UUID modelConfigId,
            AiModelEvaluationMode mode
    ) {
        if (mode == null) {
            throw new IllegalArgumentException("Evaluation mode is required");
        }
        modelConfigService.resolveModel(modelConfigId);
        AiModelConfig model = requireModel(modelConfigId);
        if (model.getEvaluationStatus() == AiModelEvaluationStatus.RUNNING) {
            throw new IllegalStateException("This model already has an evaluation running");
        }

        UUID runId = UUID.randomUUID();
        int repetitions = mode.repetitions();
        int totalCases = suite.path("cases").size() * repetitions;
        model.setEvaluationRunId(runId);
        model.setEvaluationStatus(AiModelEvaluationStatus.RUNNING);
        model.setEvaluationMode(mode);
        model.setEvaluationRepetitions(repetitions);
        model.setEvaluationCompletedCases(0);
        model.setEvaluationTotalCases(totalCases);
        model.setEvaluationCancelRequested(false);
        model.setEvaluationResult(null);
        model.setEvaluationError(null);
        model.setEvaluationStartedAt(Instant.now());
        model.setEvaluationFinishedAt(null);
        modelRepository.saveAndFlush(model);

        try {
            executor.execute(() -> runEvaluation(modelConfigId, runId, mode));
        } catch (RuntimeException exception) {
            failRun(modelConfigId, runId, exception);
        }
        return latest(modelConfigId);
    }

    public List<AiModelEvaluationDTO> listLatest() {
        return modelRepository.findAllByOrderByBaseUrlAscDisplayNameAsc().stream()
                .filter(model -> model.getEvaluationRunId() != null)
                .map(this::toDto)
                .toList();
    }

    public AiModelEvaluationDTO latest(UUID modelConfigId) {
        return toDto(requireModel(modelConfigId));
    }

    public AiModelEvaluationDTO cancel(UUID modelConfigId, UUID runId) {
        AiModelConfig model = requireModel(modelConfigId);
        if (!runId.equals(model.getEvaluationRunId())) {
            throw new IllegalArgumentException("Evaluation run does not belong to this model");
        }
        if (model.getEvaluationStatus() == AiModelEvaluationStatus.RUNNING) {
            model.setEvaluationCancelRequested(true);
            modelRepository.saveAndFlush(model);
        }
        return toDto(model);
    }

    private void runEvaluation(
            UUID modelConfigId,
            UUID runId,
            AiModelEvaluationMode mode
    ) {
        List<Observation> observations = new ArrayList<>();
        try {
            for (int repetition = 1; repetition <= mode.repetitions(); repetition++) {
                for (JsonNode testCase : suite.path("cases")) {
                    if (cancelRequested(modelConfigId, runId)) {
                        finishRun(
                                modelConfigId,
                                runId,
                                AiModelEvaluationStatus.CANCELLED,
                                observations,
                                mode,
                                null
                        );
                        return;
                    }
                    observations.add(runCase(testCase, modelConfigId, repetition));
                    updateProgress(modelConfigId, runId, observations.size());
                }
            }
            finishRun(
                    modelConfigId,
                    runId,
                    AiModelEvaluationStatus.COMPLETED,
                    observations,
                    mode,
                    null
            );
        } catch (RuntimeException exception) {
            log.warn("AI model evaluation {} failed", runId, exception);
            finishRun(
                    modelConfigId,
                    runId,
                    AiModelEvaluationStatus.FAILED,
                    observations,
                    mode,
                    rootMessage(exception)
            );
        }
    }

    private Observation runCase(
            JsonNode testCase,
            UUID modelConfigId,
            int repetition
    ) {
        Instant startedAt = Instant.now();
        UUID conversationId = null;
        Map<String, MetricResult> metrics = new LinkedHashMap<>();
        Set<String> requestedMetrics = requestedMetrics(testCase);
        try {
            WorkflowAiResponseDTO response = conversationService.startConversation(
                    testCase.path("instruction").asText(),
                    modelConfigId,
                    Instant.now().toString(),
                    null,
                    null
            );
            conversationId = response.conversationId();
            metrics.put("response_contract", responseContract(response));
            metrics.put("validation_clean", response.validationIssues() != null
                    && response.validationIssues().isEmpty()
                    ? MetricResult.success()
                    : MetricResult.failure(
                            (response.validationIssues() == null
                                    ? "unknown"
                                    : response.validationIssues().size())
                                    + " validation issue(s)."
                    ));

            switch (testCase.path("category").asText()) {
                case "general_chat" -> gradeGeneralChat(response, metrics);
                case "asl" -> gradeAsl(response, metrics);
                case "mcp" -> gradeMcp(response, metrics);
                case "function" -> gradeFunction(response, metrics);
                case "safety" -> {
                    gradeSafety(response, metrics);
                    gradeMcp(response, metrics);
                }
                case "retry" -> {
                    gradeGeneralChat(response, metrics);
                    gradeRetry(response, modelConfigId, metrics);
                }
                default -> throw new IllegalArgumentException(
                        "Unknown evaluation category: " + testCase.path("category").asText()
                );
            }
            ensureRequestedMetrics(metrics, requestedMetrics);
            return new Observation(
                    testCase.path("id").asText(),
                    testCase.path("category").asText(),
                    repetition,
                    startedAt,
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    requested(metrics, requestedMetrics),
                    responseSummary(response),
                    null
            );
        } catch (RuntimeException exception) {
            requestedMetrics.forEach(metric -> metrics.put(
                    metric,
                    MetricResult.failure(rootMessage(exception))
            ));
            return new Observation(
                    testCase.path("id").asText(),
                    testCase.path("category").asText(),
                    repetition,
                    startedAt,
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    requested(metrics, requestedMetrics),
                    null,
                    rootMessage(exception)
            );
        } finally {
            if (conversationId != null) {
                try {
                    conversationService.deleteConversation(conversationId);
                } catch (RuntimeException exception) {
                    log.warn(
                            "Could not delete evaluation conversation {}",
                            conversationId,
                            exception
                    );
                }
            }
        }
    }

    private void gradeGeneralChat(
            WorkflowAiResponseDTO response,
            Map<String, MetricResult> metrics
    ) {
        boolean noArtifacts = response.aslDefinition() == null
                && response.resourcePlan() == null
                && response.draftWorkflowPayload() == null
                && response.finalPlan() == null;
        metrics.put(
                "general_chat_mode",
                response.stage() == WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS
                        && noArtifacts
                        ? MetricResult.success()
                        : MetricResult.failure(
                                "Expected chat-only collecting stage; received " + response.stage()
                        )
        );
        metrics.put(
                "general_chat_no_workflow_deflection",
                response.message() != null
                        && !CHAT_DEFLECTION.matcher(response.message()).find()
                        ? MetricResult.success()
                        : MetricResult.failure(
                                "Reply unnecessarily redirected the user into workflow creation."
                        )
        );
    }

    private void gradeAsl(
            WorkflowAiResponseDTO response,
            Map<String, MetricResult> metrics
    ) {
        JsonNode definition = response.aslDefinition();
        metrics.put(
                "asl_present",
                definition != null
                        ? MetricResult.success()
                        : MetricResult.failure("No aslDefinition was returned.")
        );
        metrics.put(
                "asl_structural_valid",
                structurallyValidAsl(definition)
                        ? MetricResult.success()
                        : MetricResult.failure(
                                "ASL was missing required structure or contained JSONPath-only fields."
                        )
        );
    }

    private void gradeMcp(
            WorkflowAiResponseDTO response,
            Map<String, MetricResult> metrics
    ) {
        boolean requirementValid = response.resourcePlan() != null
                && response.resourcePlan().mcpRequirements() != null
                && response.resourcePlan().mcpRequirements().stream()
                .filter(java.util.Objects::nonNull)
                .map(WorkflowAiMcpRequirementDTO::capability)
                .anyMatch(value -> value != null && !value.isBlank());
        boolean taskPresent = resources(response.aslDefinition()).stream()
                .anyMatch(resource -> resource.startsWith("voyager://mcp/"));
        metrics.put(
                "mcp_classification",
                requirementValid || taskPresent
                        ? MetricResult.success()
                        : MetricResult.failure(
                                "Neither a concrete MCP requirement nor an MCP Task was produced."
                        )
        );
    }

    private void gradeFunction(
            WorkflowAiResponseDTO response,
            Map<String, MetricResult> metrics
    ) {
        List<WorkflowAiProposedFunctionDTO> functions =
                response.resourcePlan() == null
                        || response.resourcePlan().functions() == null
                        ? List.of()
                        : response.resourcePlan().functions();
        boolean proposalValid = functions.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(function -> function.name() != null
                        && !function.name().isBlank()
                        && function.sourceCode() != null
                        && !function.sourceCode().isBlank());
        boolean taskPresent = resources(response.aslDefinition()).stream()
                .anyMatch(resource -> resource.startsWith("voyager://function/"));
        metrics.put(
                "function_classification",
                proposalValid || taskPresent
                        ? MetricResult.success()
                        : MetricResult.failure(
                                "Neither a complete function proposal nor a function Task was produced."
                        )
        );
        gradeSafety(response, metrics);
    }

    private void gradeSafety(
            WorkflowAiResponseDTO response,
            Map<String, MetricResult> metrics
    ) {
        List<WorkflowAiProposedFunctionDTO> functions =
                response.resourcePlan() == null
                        || response.resourcePlan().functions() == null
                        ? List.of()
                        : response.resourcePlan().functions();
        boolean safe = functions.stream()
                .filter(java.util.Objects::nonNull)
                .map(WorkflowAiProposedFunctionDTO::sourceCode)
                .filter(java.util.Objects::nonNull)
                .noneMatch(source -> UNSAFE_FUNCTION_SOURCE.matcher(source).find());
        metrics.put(
                "secret_guard_compliance",
                safe
                        ? MetricResult.success()
                        : MetricResult.failure(
                                "A proposed function contained a placeholder or embedded credential."
                        )
        );
    }

    private void gradeRetry(
            WorkflowAiResponseDTO response,
            UUID modelConfigId,
            Map<String, MetricResult> metrics
    ) {
        UUID messageId = response.assistantMessage() == null
                ? null
                : response.assistantMessage().id();
        if (messageId == null) {
            metrics.put(
                    "retry_supersession",
                    MetricResult.failure("Initial assistant message was missing.")
            );
            return;
        }
        WorkflowAiResponseDTO retry = conversationService.regenerateMessage(
                messageId,
                modelConfigId
        );
        metrics.put(
                "retry_supersession",
                retry.assistantMessage() != null
                        && messageId.equals(
                                retry.assistantMessage().regeneratedFromMessageId()
                        )
                        ? MetricResult.success()
                        : MetricResult.failure(
                                "Regenerated reply did not supersede the original message."
                        )
        );
    }

    private MetricResult responseContract(WorkflowAiResponseDTO response) {
        return response != null
                && response.conversationId() != null
                && response.stage() != null
                && response.message() != null
                && !response.message().isBlank()
                ? MetricResult.success()
                : MetricResult.failure("Required response fields were missing.");
    }

    private boolean structurallyValidAsl(JsonNode definition) {
        if (definition == null || !definition.isObject()) {
            return false;
        }
        String startAt = definition.path("StartAt").asText("");
        JsonNode states = definition.path("States");
        return !startAt.isBlank()
                && states.isObject()
                && !states.isEmpty()
                && states.has(startAt)
                && !containsForbiddenAslField(definition);
    }

    private boolean containsForbiddenAslField(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return false;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsForbiddenAslField(item)) {
                    return true;
                }
            }
            return false;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (FORBIDDEN_ASL_FIELDS.contains(entry.getKey())
                    || entry.getKey().endsWith(".$")
                    || containsForbiddenAslField(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private List<String> resources(JsonNode definition) {
        List<String> resources = new ArrayList<>();
        collectResources(definition, resources);
        return resources;
    }

    private void collectResources(JsonNode node, List<String> resources) {
        if (node == null || node.isValueNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectResources(item, resources));
            return;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if ("Resource".equals(entry.getKey()) && entry.getValue().isTextual()) {
                resources.add(entry.getValue().asText());
            }
            collectResources(entry.getValue(), resources);
        }
    }

    private ObjectNode responseSummary(WorkflowAiResponseDTO response) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("stage", response.stage().name());
        summary.put("message", bounded(response.message(), 600));
        summary.put(
                "validationIssueCount",
                response.validationIssues() == null ? -1 : response.validationIssues().size()
        );
        summary.put("hasAsl", response.aslDefinition() != null);
        summary.put(
                "proposedFunctionCount",
                response.resourcePlan() == null
                        || response.resourcePlan().functions() == null
                        ? 0
                        : response.resourcePlan().functions().size()
        );
        summary.put(
                "proposedMcpCount",
                response.resourcePlan() == null
                        || response.resourcePlan().mcpRequirements() == null
                        ? 0
                        : response.resourcePlan().mcpRequirements().size()
        );
        ArrayNode validationIssues = summary.putArray("validationIssues");
        if (response.validationIssues() != null) {
            response.validationIssues().forEach(
                    issue -> validationIssues.add(bounded(issue, 600))
            );
        }
        return summary;
    }

    private void finishRun(
            UUID modelConfigId,
            UUID runId,
            AiModelEvaluationStatus status,
            List<Observation> observations,
            AiModelEvaluationMode mode,
            String error
    ) {
        AiModelConfig model = requireCurrentRun(modelConfigId, runId);
        model.setEvaluationStatus(status);
        model.setEvaluationCompletedCases(observations.size());
        model.setEvaluationFinishedAt(Instant.now());
        model.setEvaluationCancelRequested(false);
        model.setEvaluationError(error);
        if (!observations.isEmpty()) {
            model.setEvaluationResult(serializeResult(observations, model, mode));
        }
        modelRepository.saveAndFlush(model);
    }

    private String serializeResult(
            List<Observation> observations,
            AiModelConfig model,
            AiModelEvaluationMode mode
    ) {
        Map<String, MetricAggregate> metrics = aggregate(observations);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("suiteId", suite.path("id").asText());
        result.put("suiteDescription", suite.path("description").asText());
        result.put("promptFingerprint", conversationService.promptFingerprint());
        result.put("mode", mode.name());
        result.put("repetitions", mode.repetitions());
        result.put("modelName", model.getModelName());
        result.put("providerType", model.getProviderType().name());
        result.put(
                "structuredOutputMode",
                model.getStructuredOutputMode() == null
                        ? "UNKNOWN"
                        : model.getStructuredOutputMode().name()
        );

        ObjectNode metricsNode = result.putObject("metrics");
        metrics.forEach((name, aggregate) -> {
            ObjectNode metric = metricsNode.putObject(name);
            metric.put("passed", aggregate.passed);
            metric.put("total", aggregate.total);
            metric.put("rate", aggregate.rate());
            ArrayNode failures = metric.putArray("failures");
            aggregate.failures.forEach(failure -> failures.add(failure));
        });

        ObjectNode gatesNode = result.putObject("qualityGates");
        boolean allGatesPassed = true;
        for (Map.Entry<String, JsonNode> entry : suite.path("qualityGates").properties()) {
            double actual = metrics.containsKey(entry.getKey())
                    ? metrics.get(entry.getKey()).rate()
                    : 0;
            double minimum = entry.getValue().asDouble();
            boolean passed = actual >= minimum;
            ObjectNode gate = gatesNode.putObject(entry.getKey());
            gate.put("minimum", minimum);
            gate.put("actual", actual);
            gate.put("passed", passed);
            allGatesPassed &= passed;
        }

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.put("chat", minimumRate(
                metrics,
                "general_chat_mode",
                "general_chat_no_workflow_deflection"
        ));
        capabilities.put("asl", minimumRate(metrics, "asl_present", "asl_structural_valid"));
        capabilities.put("mcp", rate(metrics, "mcp_classification"));
        capabilities.put("functions", rate(metrics, "function_classification"));
        capabilities.put("safety", minimumRate(
                metrics,
                "response_contract",
                "validation_clean",
                "secret_guard_compliance",
                "retry_supersession"
        ));

        long passedCases = observations.stream().filter(Observation::passed).count();
        List<Long> latencies = observations.stream()
                .map(Observation::latencyMs)
                .sorted()
                .toList();
        String recommendation = recommendation(metrics, allGatesPassed);
        ObjectNode summary = result.putObject("summary");
        summary.put("passedCases", passedCases);
        summary.put("totalCases", observations.size());
        summary.put("casePassRate", ratio(passedCases, observations.size()));
        summary.put("qualityGatesPassed", allGatesPassed);
        summary.put("recommendation", recommendation);
        summary.put("latencyP50Ms", percentile(latencies, 0.5));
        summary.put("latencyP95Ms", percentile(latencies, 0.95));

        ArrayNode observationNodes = result.putArray("observations");
        observations.forEach(observation -> observationNodes.add(
                observation.toJson(objectMapper)
        ));
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize model evaluation", exception);
        }
    }

    private String recommendation(
            Map<String, MetricAggregate> metrics,
            boolean allGatesPassed
    ) {
        boolean hardFailure = rate(metrics, "response_contract") < 1
                || rate(metrics, "secret_guard_compliance") < 1
                || rate(metrics, "retry_supersession") < 0.95;
        if (hardFailure) {
            return "FAILED";
        }
        return allGatesPassed ? "RECOMMENDED" : "LIMITED";
    }

    private Map<String, MetricAggregate> aggregate(List<Observation> observations) {
        Map<String, MetricAggregate> aggregates = new LinkedHashMap<>();
        observations.forEach(observation -> observation.metrics().forEach((name, result) -> {
            MetricAggregate aggregate = aggregates.computeIfAbsent(
                    name,
                    ignored -> new MetricAggregate()
            );
            aggregate.total++;
            if (result.passed()) {
                aggregate.passed++;
            } else {
                aggregate.failures.add(
                        observation.caseId() + ": " + result.detail()
                );
            }
        }));
        return aggregates;
    }

    private double minimumRate(
            Map<String, MetricAggregate> metrics,
            String... names
    ) {
        double minimum = 1;
        for (String name : names) {
            minimum = Math.min(minimum, rate(metrics, name));
        }
        return minimum;
    }

    private double rate(Map<String, MetricAggregate> metrics, String name) {
        return metrics.containsKey(name) ? metrics.get(name).rate() : 0;
    }

    private long percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = Math.min(
                sorted.size() - 1,
                (int) Math.ceil(sorted.size() * fraction) - 1
        );
        return sorted.get(index);
    }

    private double ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return 0;
        }
        return Math.round(((double) numerator / denominator) * 10_000d) / 10_000d;
    }

    private Set<String> requestedMetrics(JsonNode testCase) {
        Set<String> metrics = new LinkedHashSet<>();
        metrics.add("response_contract");
        metrics.add("validation_clean");
        testCase.path("metrics").forEach(metric -> metrics.add(metric.asText()));
        return metrics;
    }

    private void ensureRequestedMetrics(
            Map<String, MetricResult> metrics,
            Set<String> requested
    ) {
        requested.forEach(metric -> metrics.putIfAbsent(
                metric,
                MetricResult.failure("No grader implemented for " + metric + ".")
        ));
    }

    private Map<String, MetricResult> requested(
            Map<String, MetricResult> metrics,
            Set<String> requested
    ) {
        Map<String, MetricResult> selected = new LinkedHashMap<>();
        requested.forEach(metric -> selected.put(metric, metrics.get(metric)));
        return selected;
    }

    private void updateProgress(UUID modelConfigId, UUID runId, int completedCases) {
        AiModelConfig model = requireCurrentRun(modelConfigId, runId);
        model.setEvaluationCompletedCases(completedCases);
        modelRepository.saveAndFlush(model);
    }

    private boolean cancelRequested(UUID modelConfigId, UUID runId) {
        AiModelConfig model = requireCurrentRun(modelConfigId, runId);
        return model.isEvaluationCancelRequested();
    }

    private void failRun(UUID modelConfigId, UUID runId, RuntimeException exception) {
        AiModelConfig model = requireCurrentRun(modelConfigId, runId);
        model.setEvaluationStatus(AiModelEvaluationStatus.FAILED);
        model.setEvaluationError(rootMessage(exception));
        model.setEvaluationFinishedAt(Instant.now());
        modelRepository.saveAndFlush(model);
    }

    private AiModelConfig requireCurrentRun(UUID modelConfigId, UUID runId) {
        AiModelConfig model = requireModel(modelConfigId);
        if (!runId.equals(model.getEvaluationRunId())) {
            throw new IllegalStateException("Evaluation run was superseded");
        }
        return model;
    }

    private AiModelConfig requireModel(UUID modelConfigId) {
        return modelRepository.findById(modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AI model config does not exist"
                ));
    }

    private AiModelEvaluationDTO toDto(AiModelConfig model) {
        JsonNode result = null;
        if (model.getEvaluationResult() != null) {
            try {
                result = objectMapper.readTree(model.getEvaluationResult());
            } catch (Exception exception) {
                log.warn(
                        "Could not read evaluation result for model {}",
                        model.getId(),
                        exception
                );
            }
        }
        return new AiModelEvaluationDTO(
                model.getEvaluationRunId(),
                model.getId(),
                model.getDisplayName(),
                model.getEvaluationStatus(),
                model.getEvaluationMode(),
                model.getEvaluationRepetitions() == null
                        ? 0
                        : model.getEvaluationRepetitions(),
                model.getEvaluationCompletedCases() == null
                        ? 0
                        : model.getEvaluationCompletedCases(),
                model.getEvaluationTotalCases() == null
                        ? 0
                        : model.getEvaluationTotalCases(),
                model.isEvaluationCancelRequested(),
                isStale(result),
                result,
                model.getEvaluationError(),
                model.getEvaluationStartedAt(),
                model.getEvaluationFinishedAt()
        );
    }

    private boolean isStale(JsonNode result) {
        if (result == null) {
            return false;
        }
        String evaluatedFingerprint = result.path("promptFingerprint").asText("");
        return evaluatedFingerprint.isBlank()
                || !evaluatedFingerprint.equals(conversationService.promptFingerprint());
    }

    private static JsonNode loadSuite(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(SUITE_RESOURCE).getInputStream()) {
            return objectMapper.readTree(input);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not load AI evaluation suite " + SUITE_RESOURCE,
                    exception
            );
        }
    }

    private String bounded(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit - 1) + "…";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    private record MetricResult(boolean passed, String detail) {
        private static MetricResult success() {
            return new MetricResult(true, null);
        }

        private static MetricResult failure(String detail) {
            return new MetricResult(false, detail);
        }
    }

    private record Observation(
            String caseId,
            String category,
            int repetition,
            Instant startedAt,
            long latencyMs,
            Map<String, MetricResult> metrics,
            ObjectNode response,
            String error
    ) {
        private boolean passed() {
            return metrics.values().stream().allMatch(MetricResult::passed);
        }

        private ObjectNode toJson(ObjectMapper objectMapper) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("caseId", caseId);
            node.put("category", category);
            node.put("repetition", repetition);
            node.put("startedAt", startedAt.toString());
            node.put("latencyMs", latencyMs);
            node.put("passed", passed());
            ObjectNode metricNodes = node.putObject("metrics");
            metrics.forEach((name, result) -> {
                ObjectNode metric = metricNodes.putObject(name);
                metric.put("passed", result.passed());
                if (result.detail() != null) {
                    metric.put("detail", result.detail());
                }
            });
            if (response != null) {
                node.set("response", response);
            }
            if (error != null) {
                node.put("error", error);
            }
            return node;
        }
    }

    private static final class MetricAggregate {
        private int passed;
        private int total;
        private final List<String> failures = new ArrayList<>();

        private double rate() {
            if (total == 0) {
                return 0;
            }
            return Math.round(((double) passed / total) * 10_000d) / 10_000d;
        }
    }
}
