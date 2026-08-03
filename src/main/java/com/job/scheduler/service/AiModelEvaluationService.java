package com.job.scheduler.service;

import com.job.scheduler.dto.AiModelEvaluationDTO;
import com.job.scheduler.dto.AiModelEvaluationHistoryDTO;
import com.job.scheduler.dto.WorkflowAiMcpRequirementDTO;
import com.job.scheduler.dto.WorkflowAiProposedFunctionDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.AiModelEvaluationRun;
import com.job.scheduler.enums.AiModelEvaluationMode;
import com.job.scheduler.enums.AiModelEvaluationStatus;
import com.job.scheduler.enums.WorkflowAiConversationStage;
import com.job.scheduler.repository.AiModelConfigRepository;
import com.job.scheduler.repository.AiModelEvaluationRunRepository;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AiModelEvaluationService {
    private static final Duration GENERAL_CHAT_CASE_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration COLD_START_CASE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration WORKFLOW_CASE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration RETRY_ATTEMPT_TIMEOUT = Duration.ofSeconds(45);
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
    private final AiModelEvaluationRunRepository runRepository;
    private final AiModelConfigService modelConfigService;
    private final WorkflowAiConversationService conversationService;
    private final AiModelEvaluationJudgeService judgeService;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final JsonNode suite;
    private final ConcurrentMap<UUID, Thread> activeEvaluationThreads = new ConcurrentHashMap<>();

    public AiModelEvaluationService(
            AiModelConfigRepository modelRepository,
            AiModelEvaluationRunRepository runRepository,
            AiModelConfigService modelConfigService,
            WorkflowAiConversationService conversationService,
            AiModelEvaluationJudgeService judgeService,
            ObjectMapper objectMapper,
            @Qualifier("aiModelEvaluationExecutor") Executor executor
    ) {
        this.modelRepository = modelRepository;
        this.runRepository = runRepository;
        this.modelConfigService = modelConfigService;
        this.conversationService = conversationService;
        this.judgeService = judgeService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.suite = loadSuite(objectMapper);
    }

    public synchronized AiModelEvaluationDTO start(
            UUID modelConfigId,
            AiModelEvaluationMode mode,
            UUID judgeModelConfigId
    ) {
        if (mode == null) {
            throw new IllegalArgumentException("Evaluation mode is required");
        }
        modelConfigService.resolveModel(modelConfigId);
        AiModelConfig model = requireModel(modelConfigId);
        if (model.getEvaluationStatus() == AiModelEvaluationStatus.RUNNING) {
            throw new IllegalStateException("This model already has an evaluation running");
        }
        // Resolve the judge before mutating any run state so a bad judge id fails the request
        // instead of leaving the model stuck in a RUNNING evaluation.
        JudgeContext judgeContext = null;
        if (judgeModelConfigId != null) {
            AiModelConfig judgeConfig = modelConfigService.resolveModel(judgeModelConfigId);
            judgeContext = new JudgeContext(
                    judgeConfig,
                    judgeService.resolve(judgeConfig),
                    suite.path("judge").path("passScore")
                            .asInt(AiModelEvaluationJudgeService.DEFAULT_PASS_SCORE)
            );
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
        syncHistory(model);

        try {
            JudgeContext judge = judgeContext;
            executor.execute(() -> runEvaluation(modelConfigId, runId, mode, judge));
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

    public AiModelEvaluationHistoryDTO history(UUID modelConfigId, int requestedPage, int requestedSize) {
        requireModel(modelConfigId);
        int page = Math.max(0, requestedPage);
        int size = Math.max(1, Math.min(50, requestedSize));
        Page<AiModelEvaluationRun> history = runRepository
                .findByModelConfigIdOrderByStartedAtDesc(
                        modelConfigId,
                        PageRequest.of(page, size)
                );
        return new AiModelEvaluationHistoryDTO(
                history.getContent().stream().map(this::toDto).toList(),
                history.getNumber(),
                history.getSize(),
                history.getTotalElements(),
                history.getTotalPages()
        );
    }

    public AiModelEvaluationDTO cancel(UUID modelConfigId, UUID runId) {
        AiModelConfig model = requireModel(modelConfigId);
        if (!runId.equals(model.getEvaluationRunId())) {
            throw new IllegalArgumentException("Evaluation run does not belong to this model");
        }
        if (model.getEvaluationStatus() == AiModelEvaluationStatus.RUNNING) {
            // Cancellation is terminal from the user's perspective. Do not leave the UI displaying
            // "Stopping" for the duration of a slow or wedged local-model HTTP request.
            model.setEvaluationStatus(AiModelEvaluationStatus.CANCELLED);
            model.setEvaluationCancelRequested(false);
            model.setEvaluationFinishedAt(Instant.now());
            model.setEvaluationResult(null);
            model.setEvaluationError(null);
            modelRepository.saveAndFlush(model);
            syncHistory(model);
            Thread worker = activeEvaluationThreads.get(runId);
            if (worker != null) {
                worker.interrupt();
            }
        }
        return toDto(model);
    }

    private void runEvaluation(
            UUID modelConfigId,
            UUID runId,
            AiModelEvaluationMode mode,
            JudgeContext judgeContext
    ) {
        List<Observation> observations = new ArrayList<>();
        activeEvaluationThreads.put(runId, Thread.currentThread());
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
                                null,
                                judgeContext
                        );
                        return;
                    }
                    observations.add(runCase(testCase, modelConfigId, repetition, judgeContext));
                    if (cancelRequested(modelConfigId, runId)) {
                        finishRun(
                                modelConfigId,
                                runId,
                                AiModelEvaluationStatus.CANCELLED,
                                observations,
                                mode,
                                null,
                                judgeContext
                        );
                        return;
                    }
                    updateProgress(modelConfigId, runId, observations);
                }
            }
            finishRun(
                    modelConfigId,
                    runId,
                    AiModelEvaluationStatus.COMPLETED,
                    observations,
                    mode,
                    null,
                    judgeContext
            );
        } catch (RuntimeException exception) {
            if (cancelRequested(modelConfigId, runId)) {
                // Clear the interrupt before persisting final state through JDBC.
                Thread.interrupted();
                finishRun(
                        modelConfigId,
                        runId,
                        AiModelEvaluationStatus.CANCELLED,
                        observations,
                        mode,
                        null,
                        judgeContext
                );
            } else {
                log.warn("AI model evaluation {} failed", runId, exception);
                finishRun(
                        modelConfigId,
                        runId,
                        AiModelEvaluationStatus.FAILED,
                        observations,
                        mode,
                        rootMessage(exception),
                        judgeContext
                );
            }
        } finally {
            activeEvaluationThreads.remove(runId, Thread.currentThread());
        }
    }

    private Observation runCase(
            JsonNode testCase,
            UUID modelConfigId,
            int repetition,
            JudgeContext judgeContext
    ) {
        Instant startedAt = Instant.now();
        UUID conversationId = null;
        Map<String, MetricResult> metrics = new LinkedHashMap<>();
        Set<String> requestedMetrics = requestedMetrics(testCase);
        Duration caseTimeout = "chat-greeting".equals(testCase.path("id").asText())
                ? COLD_START_CASE_TIMEOUT
                : Set.of("general_chat", "retry").contains(
                        testCase.path("category").asText()
                )
                        ? GENERAL_CHAT_CASE_TIMEOUT
                        : WORKFLOW_CASE_TIMEOUT;
        try {
            WorkflowAiResponseDTO response = conversationService.startEvaluationConversation(
                    testCase.path("instruction").asText(),
                    modelConfigId,
                    Instant.now().toString(),
                    caseTimeout
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
                case "tool_calling" -> gradeToolCalling(response, metrics);
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
            // Latency is the evaluated model's time only; the judge call happens after the clock
            // stops so choosing a slow judge cannot skew the candidate's latency percentiles.
            long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
            Map<String, MetricResult> evaluatedMetrics = requested(metrics, requestedMetrics);
            List<String> deterministicFailures = evaluatedMetrics.entrySet().stream()
                    .filter(entry -> !entry.getValue().passed())
                    .map(entry -> entry.getKey()
                            + (entry.getValue().detail() == null
                                    ? ""
                                    : ": " + entry.getValue().detail()))
                    .toList();
            AiModelEvaluationJudgeService.Judgment judgment = judgeContext == null
                    ? null
                    : judgeService.judge(
                            judgeContext.model(),
                            judgeContext.config(),
                            testCase,
                            response,
                            deterministicFailures,
                            judgeContext.passScore()
                    );
            return new Observation(
                    testCase.path("id").asText(),
                    testCase.path("category").asText(),
                    testCase.path("instruction").asText(),
                    repetition,
                    startedAt,
                    latencyMs,
                    evaluatedMetrics,
                    responseSummary(response),
                    null,
                    judgment
            );
        } catch (RuntimeException exception) {
            String failure = rootMessage(exception);
            if (failure != null && failure.toLowerCase(Locale.ROOT).contains("timed out")) {
                failure = "Model/provider did not respond within the "
                        + caseTimeout.toSeconds() + "s evaluation limit.";
            }
            String metricFailure = failure;
            requestedMetrics.forEach(metric -> metrics.put(
                    metric,
                    MetricResult.failure(metricFailure)
            ));
            return new Observation(
                    testCase.path("id").asText(),
                    testCase.path("category").asText(),
                    testCase.path("instruction").asText(),
                    repetition,
                    startedAt,
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    requested(metrics, requestedMetrics),
                    null,
                    metricFailure,
                    null
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

    private void gradeToolCalling(
            WorkflowAiResponseDTO response,
            Map<String, MetricResult> metrics
    ) {
        var telemetry = response.assistantMessage() == null
                ? null : response.assistantMessage().toolTelemetry();
        boolean used = telemetry != null && telemetry.toolLoopUsed();
        metrics.put("tool_loop_used", used
                ? MetricResult.success()
                : MetricResult.failure("The turn did not use the bounded tool loop."));
        metrics.put("tool_native_or_fallback", used
                        && (telemetry.nativeToolCalls() > 0 || telemetry.automaticToolCalls() > 0)
                ? MetricResult.success()
                : MetricResult.failure("No native or automatic catalog tool call was recorded."));
        metrics.put("tool_loop_bounded", used && telemetry.toolModelCalls() <= 6
                ? MetricResult.success()
                : MetricResult.failure("The tool loop was absent or exceeded six model rounds."));
        metrics.put("tool_final_validation_clean", used
                        && response.validationIssues() != null
                        && response.validationIssues().isEmpty()
                ? MetricResult.success()
                : MetricResult.failure("The tool-driven final response did not pass exact validation."));
        boolean hasGroundedTask = resources(response.aslDefinition()).stream()
                .anyMatch(resource -> resource.startsWith("voyager://"));
        metrics.put("tool_selection_grounded", used && hasGroundedTask
                ? MetricResult.success()
                : MetricResult.failure("No grounded Voyager Task URI was returned."));
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
        WorkflowAiResponseDTO retry;
        try {
            retry = conversationService.regenerateEvaluationMessage(
                    messageId,
                    modelConfigId,
                    RETRY_ATTEMPT_TIMEOUT
            );
        } catch (RuntimeException exception) {
            String failure = rootMessage(exception);
            if (failure != null && failure.toLowerCase(Locale.ROOT).contains("timed out")) {
                failure = "Retry model/provider did not respond within the "
                        + RETRY_ATTEMPT_TIMEOUT.toSeconds() + "s evaluation limit.";
            }
            metrics.put("retry_supersession", MetricResult.failure(failure));
            return;
        }
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
        // The raw model reply (including an ASL that was rejected in validation) so a failure can be
        // diagnosed from the UI without re-running the model.
        summary.put("rawModelReply", bounded(response.rawAssistantReply(), 4000));
        summary.put(
                "validationIssueCount",
                response.validationIssues() == null ? -1 : response.validationIssues().size()
        );
        summary.put("hasAsl", response.aslDefinition() != null);
        if (response.assistantMessage() != null
                && response.assistantMessage().toolTelemetry() != null) {
            var telemetry = response.assistantMessage().toolTelemetry();
            summary.put("toolLoopUsed", telemetry.toolLoopUsed());
            summary.put("nativeToolCalls", telemetry.nativeToolCalls());
            summary.put("automaticToolCalls", telemetry.automaticToolCalls());
            summary.put("toolModelCalls", telemetry.toolModelCalls());
            summary.put("toolRejectedFinals", telemetry.rejectedFinals());
            summary.put("toolFallbackReason", telemetry.fallbackReason());
            summary.put("estimatedNetInputTokensSaved", telemetry.estimatedNetInputTokensSaved());
        }
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
            String error,
            JudgeContext judgeContext
    ) {
        AiModelConfig model = requireCurrentRun(modelConfigId, runId);
        if (model.getEvaluationStatus() == AiModelEvaluationStatus.CANCELLED
                && status != AiModelEvaluationStatus.CANCELLED) {
            return;
        }
        model.setEvaluationStatus(status);
        model.setEvaluationCompletedCases(observations.size());
        model.setEvaluationFinishedAt(Instant.now());
        model.setEvaluationCancelRequested(false);
        model.setEvaluationError(error);
        if (!observations.isEmpty()) {
            model.setEvaluationResult(serializeResult(observations, model, mode, judgeContext));
        }
        modelRepository.saveAndFlush(model);
        syncHistory(model);
    }

    private String serializeResult(
            List<Observation> observations,
            AiModelConfig model,
            AiModelEvaluationMode mode,
            JudgeContext judgeContext
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
        capabilities.put("tools", minimumRate(
                metrics,
                "tool_loop_used",
                "tool_native_or_fallback",
                "tool_loop_bounded",
                "tool_final_validation_clean",
                "tool_selection_grounded"
        ));
        capabilities.put("safety", minimumRate(
                metrics,
                "response_contract",
                "validation_clean",
                "secret_guard_compliance",
                "retry_supersession"
        ));

        if (judgeContext != null) {
            result.set("judge", judgeSummary(observations, judgeContext));
        }

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

        result.set("observations", observationsJson(observations));
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize model evaluation", exception);
        }
    }

    private ArrayNode observationsJson(List<Observation> observations) {
        ArrayNode observationNodes = objectMapper.createArrayNode();
        observations.forEach(observation -> observationNodes.add(
                observation.toJson(objectMapper)
        ));
        return observationNodes;
    }

    /**
     * Persists the cases finished so far as a partial {@code {"observations":[...]}} payload while the
     * run is still going, so the polling UI can show each case (prompt, metrics, response) live instead
     * of waiting for the whole run to complete. {@link #finishRun} overwrites it with the full result.
     */
    private String serializeProgress(List<Observation> observations) {
        ObjectNode progress = objectMapper.createObjectNode();
        progress.set("observations", observationsJson(observations));
        try {
            return objectMapper.writeValueAsString(progress);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize evaluation progress", exception);
        }
    }

    /**
     * Aggregates the advisory LLM-judge layer. Judgments never feed metrics, quality gates, or the
     * recommendation: a weak judge verdict flags quality for a human, it does not fail the run.
     */
    private ObjectNode judgeSummary(List<Observation> observations, JudgeContext judgeContext) {
        ObjectNode judgeNode = objectMapper.createObjectNode();
        judgeNode.put("modelConfigId", judgeContext.config().getId().toString());
        judgeNode.put("modelName", judgeContext.config().getModelName());
        judgeNode.put("displayName", judgeContext.config().getDisplayName());
        judgeNode.put("passScore", judgeContext.passScore());

        List<Observation> judged = observations.stream()
                .filter(observation -> observation.judgment() != null)
                .toList();
        List<AiModelEvaluationJudgeService.Judgment> scored = judged.stream()
                .map(Observation::judgment)
                .filter(AiModelEvaluationJudgeService.Judgment::scoredSuccessfully)
                .toList();
        long passed = scored.stream()
                .filter(judgment -> Boolean.TRUE.equals(judgment.passed()))
                .count();
        double meanScore = scored.isEmpty()
                ? 0
                : scored.stream()
                        .mapToInt(AiModelEvaluationJudgeService.Judgment::score)
                        .average()
                        .orElse(0);

        judgeNode.put("judgedCases", judged.size());
        judgeNode.put("scoredCases", scored.size());
        judgeNode.put("erroredCases", judged.size() - scored.size());
        judgeNode.put("meanScore", Math.round(meanScore * 100d) / 100d);
        judgeNode.put("passRate", ratio(passed, scored.size()));
        judgeNode.put("verdict", judgeVerdict(scored.size(), ratio(passed, scored.size())));

        ArrayNode failures = judgeNode.putArray("failures");
        ArrayNode errors = judgeNode.putArray("errors");
        judged.forEach(observation -> {
            AiModelEvaluationJudgeService.Judgment judgment = observation.judgment();
            if (!judgment.scoredSuccessfully()) {
                errors.add(observation.caseId() + ": " + judgment.error());
            } else if (!Boolean.TRUE.equals(judgment.passed())) {
                failures.add(
                        observation.caseId() + ": " + judgment.rationale()
                                + " (score " + judgment.score() + ")"
                );
            }
        });
        return judgeNode;
    }

    private String judgeVerdict(int scoredCases, double passRate) {
        if (scoredCases == 0) {
            return "UNSCORED";
        }
        if (passRate >= 0.8) {
            return "STRONG";
        }
        return passRate >= 0.5 ? "MIXED" : "WEAK";
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

    private void updateProgress(
            UUID modelConfigId,
            UUID runId,
            List<Observation> observations
    ) {
        AiModelConfig model = requireCurrentRun(modelConfigId, runId);
        model.setEvaluationCompletedCases(observations.size());
        model.setEvaluationResult(serializeProgress(observations));
        modelRepository.saveAndFlush(model);
        syncHistory(model);
    }

    private boolean cancelRequested(UUID modelConfigId, UUID runId) {
        AiModelConfig model = requireCurrentRun(modelConfigId, runId);
        return model.getEvaluationStatus() == AiModelEvaluationStatus.CANCELLED
                || model.isEvaluationCancelRequested();
    }

    private void failRun(UUID modelConfigId, UUID runId, RuntimeException exception) {
        AiModelConfig model = requireCurrentRun(modelConfigId, runId);
        model.setEvaluationStatus(AiModelEvaluationStatus.FAILED);
        model.setEvaluationError(rootMessage(exception));
        model.setEvaluationFinishedAt(Instant.now());
        modelRepository.saveAndFlush(model);
        syncHistory(model);
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
        JsonNode stored = null;
        if (model.getEvaluationResult() != null) {
            try {
                stored = objectMapper.readTree(model.getEvaluationResult());
            } catch (Exception exception) {
                log.warn(
                        "Could not read evaluation result for model {}",
                        model.getId(),
                        exception
                );
            }
        }
        // While a run is in progress the stored payload is a partial {observations:[...]} snapshot, not
        // a full result. Surface those live for the polling UI and keep result null until the run ends.
        boolean running = model.getEvaluationStatus() == AiModelEvaluationStatus.RUNNING;
        JsonNode result = running ? null : stored;
        JsonNode progressObservations = running && stored != null
                ? stored.get("observations")
                : null;
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
                progressObservations,
                model.getEvaluationError(),
                model.getEvaluationStartedAt(),
                model.getEvaluationFinishedAt()
        );
    }

    private AiModelEvaluationDTO toDto(AiModelEvaluationRun run) {
        JsonNode stored = parseStoredResult(run.getResult(), run.getModelConfigId());
        boolean running = run.getStatus() == AiModelEvaluationStatus.RUNNING;
        JsonNode result = running ? null : stored;
        JsonNode progressObservations = running && stored != null
                ? stored.get("observations")
                : null;
        return new AiModelEvaluationDTO(
                run.getId(),
                run.getModelConfigId(),
                run.getModelDisplayName(),
                run.getStatus(),
                run.getMode(),
                run.getRepetitions(),
                run.getCompletedCases(),
                run.getTotalCases(),
                run.isCancelRequested(),
                isStale(result),
                result,
                progressObservations,
                run.getError(),
                run.getStartedAt(),
                run.getFinishedAt()
        );
    }

    private JsonNode parseStoredResult(String value, UUID modelConfigId) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            log.warn("Could not read evaluation result for model {}", modelConfigId, exception);
            return null;
        }
    }

    private void syncHistory(AiModelConfig model) {
        if (model.getEvaluationRunId() == null
                || model.getEvaluationStatus() == null
                || model.getEvaluationMode() == null
                || model.getEvaluationStartedAt() == null) {
            return;
        }
        AiModelEvaluationRun run = runRepository.findById(model.getEvaluationRunId())
                .orElseGet(AiModelEvaluationRun::new);
        if (run.getId() == null) {
            run.setId(model.getEvaluationRunId());
            run.setModelConfigId(model.getId());
        }
        run.setModelDisplayName(model.getDisplayName());
        run.setStatus(model.getEvaluationStatus());
        run.setMode(model.getEvaluationMode());
        run.setRepetitions(model.getEvaluationRepetitions() == null
                ? 0
                : model.getEvaluationRepetitions());
        run.setCompletedCases(model.getEvaluationCompletedCases() == null
                ? 0
                : model.getEvaluationCompletedCases());
        run.setTotalCases(model.getEvaluationTotalCases() == null
                ? 0
                : model.getEvaluationTotalCases());
        run.setCancelRequested(model.isEvaluationCancelRequested());
        run.setResult(model.getEvaluationResult());
        run.setError(model.getEvaluationError());
        run.setStartedAt(model.getEvaluationStartedAt());
        run.setFinishedAt(model.getEvaluationFinishedAt());
        runRepository.saveAndFlush(run);
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

    private record JudgeContext(AiModelConfig config, ChatModel model, int passScore) {
    }

    private record Observation(
            String caseId,
            String category,
            String instruction,
            int repetition,
            Instant startedAt,
            long latencyMs,
            Map<String, MetricResult> metrics,
            ObjectNode response,
            String error,
            AiModelEvaluationJudgeService.Judgment judgment
    ) {
        private boolean passed() {
            return metrics.values().stream().allMatch(MetricResult::passed);
        }

        private ObjectNode toJson(ObjectMapper objectMapper) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("caseId", caseId);
            node.put("category", category);
            node.put("instruction", instruction);
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
            if (judgment != null) {
                ObjectNode judgeNode = node.putObject("judge");
                if (judgment.scoredSuccessfully()) {
                    judgeNode.put("score", judgment.score());
                    judgeNode.put("passed", judgment.passed());
                    if (judgment.rationale() != null && !judgment.rationale().isBlank()) {
                        judgeNode.put("rationale", judgment.rationale());
                    }
                } else {
                    judgeNode.put("error", judgment.error());
                }
                judgeNode.put("latencyMs", judgment.latencyMs());
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
