package com.job.scheduler.service;

import com.job.scheduler.dto.CreateWorkflowRequestDTO;
import com.job.scheduler.dto.WorkflowAiConversationDetailDTO;
import com.job.scheduler.dto.WorkflowAiConversationSummaryDTO;
import com.job.scheduler.dto.WorkflowAiMessageDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.dto.WorkflowResponseDTO;
import com.job.scheduler.dto.WorkflowAiWorkspaceRequestDTO;
import com.job.scheduler.dto.WorkflowAiWorkspaceSettingsDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.WorkflowAiConversation;
import com.job.scheduler.entity.WorkflowAiMessage;
import com.job.scheduler.enums.WorkflowAiConversationStage;
import com.job.scheduler.enums.WorkflowAiMessageRole;
import com.job.scheduler.repository.WorkflowAiConversationRepository;
import com.job.scheduler.repository.WorkflowAiMessageRepository;
import com.job.scheduler.workflow.asl.runtime.AslRuntimeCapabilityValidator;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAiConversationService {
    private static final Pattern THINKING_PATTERN = Pattern.compile(
            "(?is)<think(?:ing)?>(.*?)</think(?:ing)?>"
    );
    private static final Pattern REPEATED_CHARACTER_RUN = Pattern.compile("(.)\\1{11,}");
    private static final Pattern DISTINCTIVE_IDENTIFIER = Pattern.compile(
            "(?i)\\b(?=[a-z0-9._:/-]{4,}\\b)(?=[a-z0-9._:/-]*[a-z])(?=[a-z0-9._:/-]*[0-9])[a-z0-9._:/-]+\\b"
    );
    private static final String INVALID_AI_RESPONSE =
            "[AI_RESPONSE] The model response was not valid Voyager workflow JSON.";

    private static final String SYSTEM_PROMPT = """
            You are Voyager's workflow builder for this scheduler.
            Return strict JSON only with these fields:
            {
              "stage": "COLLECTING_WORKFLOW_DETAILS|ASL_READY|ASL_UNDER_REVIEW|COLLECTING_SCHEDULE_DETAILS|PLAN_READY",
              "message": "short assistant message for the user",
              "aslDefinition": optional JSONata-only ASL object,
              "finalPlan": optional object,
              "draftWorkflowPayload": optional object with name, cronExpression, timezone, maxAttempts, idempotencyKey, definition
            }
            ASL rules: omit QueryLanguage and Version, use JSONata expressions with {% %}, reject JSONPath fields and States.* intrinsics.
            Never return an Adaptive Card, Markdown wrapper, JSON Schema, tool-call envelope, or UI component as aslDefinition.
            aslDefinition must be the ASL machine itself with StartAt and a non-empty States object.
            Keep cron, timezone, approval, and schedule metadata outside ASL.
            When asked to recall an exact identifier, copy it verbatim from the supplied Exact source identifiers.
            Ask clarifying questions until the workflow is clear. When ASL is ready, include aslDefinition.
            If the user already has ASL open in their editor it is given to you as "Current ASL in the user's editor".
            Persisted workflow context may provide the same document as "Latest ASL definition (authoritative)".
            Treat that ASL as the source of truth, amend it in place for what the user asks, and return the whole
            amended definition as aslDefinition. Keep the states, names, and structure the user already has unless
            they ask otherwise, and never restart from scratch when an editor ASL is present.
            After ASL is approved, collect workflow name, cron expression if scheduled, timezone, and max attempts.
            When everything is ready, return PLAN_READY with finalPlan and draftWorkflowPayload.
            If the selected local model supports visible reasoning, put that reasoning before the JSON as <think>...</think>.
            The content after </think> must still be strict JSON only.
            """;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            You compact older turns from a workflow-design conversation into durable memory.
            Return only a concise factual summary, never an answer to the user.
            Preserve requirements, decisions, state names, resources, JSONata expressions,
            schedule details, corrections, unresolved questions, and explicit user preferences.
            Distinguish confirmed decisions from proposals. Do not invent missing details.
            The original request, current stage, and latest ASL are supplied separately to the
            workflow model, so focus on conversational facts that are not already represented there.
            """;

    private static final int APPROXIMATE_CHARACTERS_PER_TOKEN = 4;

    private final AiModelConfigService aiModelConfigService;
    private final WorkflowAiModelResolver modelResolver;
    private final WorkflowAiConversationRepository conversationRepository;
    private final WorkflowAiMessageRepository messageRepository;
    private final AslDefinitionValidator aslDefinitionValidator;
    private final AslRuntimeCapabilityValidator runtimeCapabilityValidator;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    @Value("${scheduler.workflow-ai.context.max-estimated-tokens:12000}")
    private int maximumContextTokens = 12000;

    @Value("${scheduler.workflow-ai.context.recent-estimated-tokens:4000}")
    private int recentContextTokens = 4000;

    @Value("${scheduler.workflow-ai.context.summary-max-characters:6000}")
    private int maximumSummaryCharacters = 6000;

    public List<WorkflowAiConversationSummaryDTO> listConversations() {
        return conversationRepository.findTop50ByOrderByUpdatedAtDesc()
                .stream()
                .map(this::summary)
                .toList();
    }

    public WorkflowAiConversationDetailDTO getConversation(UUID conversationId) {
        WorkflowAiConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow AI conversation does not exist"
                ));

        return detail(conversation);
    }

    @Transactional
    public void saveWorkspace(UUID conversationId, WorkflowAiWorkspaceRequestDTO request) {
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        if (!request.definition().isObject()) {
            throw new IllegalArgumentException("ASL definition must be a JSON object");
        }
        if (!request.canvasLayout().isObject()) {
            throw new IllegalArgumentException("Canvas layout must be a JSON object");
        }

        List<String> validationIssues = validateExecutableDefinition(request.definition());
        if (!validationIssues.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot save an invalid JSONata ASL definition: "
                            + String.join("; ", validationIssues)
            );
        }

        JsonNode workspaceSettings = objectMapper.valueToTree(request.settings());
        if (request.definition().equals(readJson(conversation.getDraftAsl()))
                && request.canvasLayout().equals(readJson(conversation.getCanvasLayout()))
                && workspaceSettings.equals(readJson(conversation.getWorkspaceSettings()))) {
            return;
        }

        // draftAsl is authoritative conversation state. Incomplete editor text stays client-side,
        // and rejected AI candidates stay in message structured_payload until they validate.
        conversation.setDraftAsl(serialize(request.definition()));
        conversation.setCanvasLayout(serialize(request.canvasLayout()));
        conversation.setWorkspaceSettings(serialize(workspaceSettings));
        conversationRepository.saveAndFlush(conversation);
    }

    @Transactional
    public WorkflowAiResponseDTO startConversation(
            String instruction,
            UUID modelConfigId,
            String userDateTime,
            JsonNode editorDefinition
    ) {
        String normalizedInstruction = requireText(instruction, "Instruction");
        AiModelConfig modelConfig = aiModelConfigService.resolveModel(modelConfigId);

        WorkflowAiConversation conversation = new WorkflowAiConversation();
        conversation.setName(generateConversationName(normalizedInstruction));
        conversation.setInitialInstruction(normalizedInstruction);
        conversation.setModelConfig(modelConfig);
        conversation.setStage(
                WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS
        );
        conversationRepository.save(conversation);

        String editorContext = editorAslContext(editorDefinition);
        List<String> editorValidationIssues = editorContext == null
                ? List.of()
                : validateExecutableDefinition(editorDefinition);
        if (editorContext != null && editorValidationIssues.isEmpty()) {
            conversation.setDraftAsl(serialize(editorDefinition));
        }
        appendMessage(
                conversation,
                WorkflowAiMessageRole.USER,
                normalizedInstruction,
                editorContext == null ? null : serialize(editorDefinition),
                modelConfig
        );

        String startTask = editorContext == null
                ? "Start from the user's first instruction."
                : "Amend the ASL already open in the user's editor.";
        if (!editorValidationIssues.isEmpty()) {
            startTask = rejectedEditorCandidateContext(editorDefinition, editorValidationIssues)
                    + "\nHelp the user correct this candidate without treating it as authoritative.";
        }
        if (userDateTime != null && !userDateTime.isBlank()) {
            startTask += "\nUser date/time context: " + userDateTime.trim();
        }
        return callAssistant(
                conversation,
                startTask
        );
    }

    @Transactional
    public WorkflowAiResponseDTO continueConversation(
            UUID conversationId,
            String message,
            UUID modelConfigId,
            JsonNode editorDefinition
    ) {
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        AiModelConfig selectedModel = aiModelConfigService.resolveModel(
                modelConfigId == null ? conversation.getModelConfig().getId() : modelConfigId
        );
        conversation.setModelConfig(selectedModel);

        // Only resend the editor ASL when it drifted from what the conversation already knows,
        // so an unchanged definition is not repeated into every prompt.
        String editorContext = null;
        List<String> editorValidationIssues = List.of();
        if (editorAslContext(editorDefinition) != null
                && !serialize(editorDefinition).equals(conversation.getDraftAsl())) {
            editorContext = editorAslContext(editorDefinition);
            editorValidationIssues = validateExecutableDefinition(editorDefinition);
            if (editorValidationIssues.isEmpty()) {
                conversation.setDraftAsl(serialize(editorDefinition));
            }
        }

        appendMessage(
                conversation,
                WorkflowAiMessageRole.USER,
                requireText(message, "Message"),
                editorContext == null ? null : serialize(editorDefinition),
                selectedModel
        );
        String task = "Continue the workflow design conversation.";
        if (!editorValidationIssues.isEmpty()) {
            task += "\n" + rejectedEditorCandidateContext(
                    editorDefinition,
                    editorValidationIssues
            );
        }
        return callAssistant(conversation, task);
    }

    @Transactional
    public WorkflowAiResponseDTO regenerateMessage(
            UUID messageId,
            UUID modelConfigId
    ) {
        WorkflowAiMessage targetMessage = messageRepository.findById(messageId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow AI message does not exist"
                ));
        if (targetMessage.getRole() != WorkflowAiMessageRole.ASSISTANT) {
            throw new IllegalArgumentException("Only assistant messages can be regenerated");
        }
        WorkflowAiConversation conversation = findForUpdate(
                targetMessage.getConversation().getId()
        );
        WorkflowAiMessage latestMessage = messageRepository
                .findFirstByConversationOrderByCreatedAtDesc(conversation)
                .orElseThrow(() -> new IllegalStateException(
                        "The conversation has no messages"
                ));
        if (!latestMessage.getId().equals(targetMessage.getId())) {
            throw new IllegalArgumentException(
                    "Only the latest assistant message can be regenerated"
            );
        }
        AiModelConfig selectedModel = aiModelConfigService.resolveModel(
                modelConfigId == null ? conversation.getModelConfig().getId() : modelConfigId
        );
        conversation.setModelConfig(selectedModel);

        // Anchor on the first attempt, so retrying repeatedly does not feed each discarded reply
        // back into the prompt and drift the answer further every time.
        List<WorkflowAiMessage> history = historyBeforeMessage(
                conversation,
                regenerationRoot(targetMessage)
        );
        return callAssistant(
                conversation,
                "Regenerate the previous assistant response. Keep the workflow state consistent.",
                selectedModel,
                targetMessage,
                history
        );
    }

    @Transactional
    public WorkflowAiResponseDTO reviewAsl(UUID conversationId, JsonNode definition) {
        if (definition == null || definition.isNull()) {
            throw new IllegalArgumentException("ASL definition cannot be null");
        }
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        List<String> validationIssues = validateExecutableDefinition(definition);
        String reviewInstruction = """
                The user edited the ASL in the editor. Compare it against the original request and the conversation.
                If the ASL is valid and matches the request, move to COLLECTING_SCHEDULE_DETAILS.
                If it needs changes, move to ASL_UNDER_REVIEW and explain the smallest correction.
                Current edited ASL:
                """ + serialize(definition);
        if (!validationIssues.isEmpty()) {
            reviewInstruction += "\nValidator issues:\n" + String.join("\n", validationIssues);
        }
        appendMessage(
                conversation,
                WorkflowAiMessageRole.USER,
                reviewInstruction,
                serialize(definition),
                conversation.getModelConfig()
        );

        WorkflowAiResponseDTO assistantResponse =
                callAssistant(conversation, "Review the edited ASL.");

        if (!validationIssues.isEmpty()) {
            conversation.setStage(WorkflowAiConversationStage.ASL_UNDER_REVIEW);
            return response(
                    conversation,
                    assistantResponse.message(),
                    definition,
                    validationIssues,
                    readJson(conversation.getFinalPlan()),
                    readDraftPayload(conversation),
                    null,
                    assistantResponse.assistantMessage()
            );
        }

        conversation.setDraftAsl(serialize(definition));
        if (conversation.getStage() == WorkflowAiConversationStage.ASL_UNDER_REVIEW
                || conversation.getStage() == WorkflowAiConversationStage.ASL_READY) {
            conversation.setStage(
                    WorkflowAiConversationStage.COLLECTING_SCHEDULE_DETAILS
            );
        }
        return response(
                conversation,
                assistantResponse.message(),
                definition,
                List.of(),
                readJson(conversation.getFinalPlan()),
                readDraftPayload(conversation),
                null,
                assistantResponse.assistantMessage()
        );
    }

    @Transactional
    public WorkflowAiResponseDTO acceptPlan(UUID conversationId) {
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        CreateWorkflowRequestDTO draftPayload = readDraftPayload(conversation);
        if (draftPayload == null) {
            draftPayload = fallbackDraftPayload(conversation);
        }
        WorkflowResponseDTO workflow = workflowService.createWorkflow(draftPayload);
        conversation.setStage(WorkflowAiConversationStage.ACCEPTED);
        appendMessage(
                conversation,
                WorkflowAiMessageRole.SYSTEM,
                "Accepted final plan and created draft workflow " + workflow.id(),
                null,
                conversation.getModelConfig()
        );
        return response(
                conversation,
                "Draft workflow created.",
                readJson(conversation.getDraftAsl()),
                List.of(),
                readJson(conversation.getFinalPlan()),
                draftPayload,
                workflow,
                null
        );
    }

    private WorkflowAiResponseDTO callAssistant(
            WorkflowAiConversation conversation,
            String task
    ) {
        return callAssistant(
                conversation,
                task,
                conversation.getModelConfig(),
                null,
                null
        );
    }

    private WorkflowAiResponseDTO callAssistant(
            WorkflowAiConversation conversation,
            String task,
            AiModelConfig modelConfig,
            WorkflowAiMessage regeneratedFromMessage,
            List<WorkflowAiMessage> historyOverride
    ) {
        ChatLanguageModel model = modelResolver.resolve(modelConfig);
        List<ChatMessage> messages = buildPrompt(
                conversation,
                task,
                historyOverride,
                model
        );
        Instant startedAt = Instant.now();
        AssistantAttempt attempt = generateAssistantAttempt(model, messages);
        List<String> validationIssues = validateAssistantAttempt(attempt.parsed());
        if (!validationIssues.isEmpty()) {
            attempt = generateAssistantAttempt(
                    model,
                    repairPrompt(messages, attempt.cleaned(), validationIssues)
            );
            validationIssues = validateAssistantAttempt(attempt.parsed());
        }
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        ParsedAssistantResponse parsed = attempt.parsed();

        JsonNode aslDefinition = parsed.aslDefinition();
        if (validationIssues.isEmpty() && aslDefinition != null) {
            conversation.setDraftAsl(serialize(aslDefinition));
            conversation.setStage(WorkflowAiConversationStage.ASL_READY);
        } else if (validationIssues.isEmpty() && parsed.draftWorkflowPayload() != null) {
            conversation.setDraftWorkflowPayload(
                    serialize(parsed.draftWorkflowPayloadNode())
            );
            conversation.setFinalPlan(serialize(parsed.finalPlan()));
            if (parsed.draftWorkflowPayload().definition() != null) {
                conversation.setDraftAsl(serialize(parsed.draftWorkflowPayload().definition()));
            }
            conversation.setStage(WorkflowAiConversationStage.PLAN_READY);
        } else if (validationIssues.isEmpty() && parsed.stage() != null) {
            conversation.setStage(parsed.stage());
        } else if (parsed.hasDefinitionCandidate()) {
            conversation.setStage(WorkflowAiConversationStage.ASL_UNDER_REVIEW);
        }
        String assistantContent = assistantContent(
                parsed,
                attempt.cleaned(),
                validationIssues
        );

        WorkflowAiMessage assistantMessage = appendMessage(
                conversation,
                WorkflowAiMessageRole.ASSISTANT,
                assistantContent,
                jsonPayloadOrNull(attempt.cleaned()),
                modelConfig,
                attempt.thinkingExtraction().thinkingContent(),
                durationMs,
                attempt.modelResponse().tokenUsage(),
                attempt.modelResponse().finishReason() == null
                        ? null
                        : attempt.modelResponse().finishReason().name(),
                regeneratedFromMessage
        );

        return response(
                conversation,
                assistantContent,
                readJson(conversation.getDraftAsl()),
                validationIssues,
                parsed.finalPlan() == null
                        ? readJson(conversation.getFinalPlan())
                        : parsed.finalPlan(),
                parsed.draftWorkflowPayload() == null
                        ? readDraftPayload(conversation)
                        : parsed.draftWorkflowPayload(),
                null,
                message(assistantMessage)
        );
    }

    private String assistantContent(
            ParsedAssistantResponse parsed,
            String cleanedResponse,
            List<String> validationIssues
    ) {
        if (validationIssues.isEmpty()) {
            return parsed.message() == null ? cleanedResponse : parsed.message();
        }
        StringBuilder message = new StringBuilder(
                "I couldn't apply the generated change because it still failed validation after one automatic repair attempt. The last valid workflow was preserved."
        );
        if (!parsed.structured()) {
            message.append("\nRaw model reply: ")
                    .append(boundedExcerpt(cleanedResponse, 1000));
        }
        return message.toString();
    }

    private AssistantAttempt generateAssistantAttempt(
            ChatLanguageModel model,
            List<ChatMessage> messages
    ) {
        Response<AiMessage> modelResponse = model.generate(messages);
        String raw = requireModelReply(modelResponse);
        ThinkingExtraction thinkingExtraction = extractThinking(raw);
        String cleaned = stripMarkdown(thinkingExtraction.answer());
        return new AssistantAttempt(
                modelResponse,
                cleaned,
                thinkingExtraction,
                parseAssistantResponse(cleaned)
        );
    }

    private List<ChatMessage> repairPrompt(
            List<ChatMessage> originalPrompt,
            String rejectedResponse,
            List<String> validationIssues
    ) {
        List<ChatMessage> repairMessages = new ArrayList<>(originalPrompt);
        repairMessages.add(AiMessage.aiMessage(rejectedResponse));
        repairMessages.add(UserMessage.userMessage("""
                Your previous response was rejected. Repair it once.
                Return exactly one strict JSON object matching the workflow response contract.
                Do not return Markdown, an Adaptive Card, a tool-call envelope, or commentary outside JSON.
                Preserve the user's requested workflow and return JSONata-only ASL when aslDefinition is present.
                Rejection reasons:
                """ + String.join("\n", validationIssues)));
        return repairMessages;
    }

    private List<String> validateAssistantAttempt(ParsedAssistantResponse parsed) {
        List<String> issues = new ArrayList<>();
        if (!parsed.structured()) {
            issues.add(INVALID_AI_RESPONSE + " " + parsed.failureReason());
            return List.copyOf(issues);
        }
        if (parsed.aslDefinition() != null) {
            issues.addAll(validateExecutableDefinition(parsed.aslDefinition()));
        }
        JsonNode payloadDefinition = parsed.draftWorkflowPayload() == null
                ? null
                : parsed.draftWorkflowPayload().definition();
        if (payloadDefinition != null) {
            issues.addAll(validateExecutableDefinition(payloadDefinition));
        }
        if (parsed.aslDefinition() != null
                && payloadDefinition != null
                && !parsed.aslDefinition().equals(payloadDefinition)) {
            issues.add(
                    "[AI_RESPONSE] aslDefinition and draftWorkflowPayload.definition must match."
            );
        }
        return List.copyOf(issues);
    }

    private String requireModelReply(Response<AiMessage> modelResponse) {
        String reply = modelResponse == null || modelResponse.content() == null
                ? null
                : modelResponse.content().text();
        if (reply == null || reply.isBlank()) {
            TokenUsage tokenUsage = modelResponse == null ? null : modelResponse.tokenUsage();
            log.warn(
                    "AI model returned an empty reply (output tokens: {}, finish reason: {})",
                    tokenUsage == null ? null : tokenUsage.outputTokenCount(),
                    modelResponse == null ? null : modelResponse.finishReason()
            );
            throw new IllegalStateException(
                    "The AI model returned an empty reply. Retry or choose a different model."
            );
        }
        return reply.trim();
    }

    private List<ChatMessage> buildPrompt(
            WorkflowAiConversation conversation,
            String task,
            List<WorkflowAiMessage> historyOverride,
            ChatLanguageModel model
    ) {
        List<WorkflowAiMessage> rawHistory = historyOverride == null
                ? messageRepository.findByConversationOrderByCreatedAtAsc(conversation)
                : historyOverride;
        List<WorkflowAiMessage> effectiveHistory = effectiveHistory(rawHistory);
        String durableContext = durableConversationContext(conversation, task);
        String exactIdentifiers = exactSourceIdentifiers(effectiveHistory);
        if (exactIdentifiers != null) {
            durableContext += "\nExact source identifiers (verbatim):\n" + exactIdentifiers;
        }
        int fixedContextTokens = estimatedTokens(SYSTEM_PROMPT)
                + estimatedTokens(durableContext);
        ContextWindow contextWindow = compactContextIfNeeded(
                conversation,
                effectiveHistory,
                model,
                fixedContextTokens,
                historyOverride == null
        );

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(SYSTEM_PROMPT));
        messages.add(SystemMessage.systemMessage(durableContext));
        if (contextWindow.summary() != null) {
            messages.add(SystemMessage.systemMessage(
                    "Summary of earlier conversation turns:\n" + contextWindow.summary()
            ));
        }
        for (WorkflowAiMessage message : contextWindow.recentMessages()) {
            if (message.getRole() == WorkflowAiMessageRole.ASSISTANT) {
                messages.add(AiMessage.aiMessage(message.getContent()));
            } else if (message.getRole() == WorkflowAiMessageRole.USER) {
                messages.add(UserMessage.userMessage(message.getContent()));
            }
        }
        return messages;
    }

    private String durableConversationContext(
            WorkflowAiConversation conversation,
            String task
    ) {
        StringBuilder context = new StringBuilder()
                .append("Current stage: ")
                .append(conversation.getStage())
                .append("\nInitial request: ")
                .append(conversation.getInitialInstruction())
                .append("\nTask: ")
                .append(task);
        if (optionalText(conversation.getDraftAsl()) != null) {
            context.append("\nLatest ASL definition (authoritative):\n")
                    .append(conversation.getDraftAsl());
        }
        if (optionalText(conversation.getFinalPlan()) != null) {
            context.append("\nLatest final plan:\n")
                    .append(conversation.getFinalPlan());
        }
        if (optionalText(conversation.getWorkspaceSettings()) != null) {
            context.append("\nLatest workflow settings (outside ASL):\n")
                    .append(conversation.getWorkspaceSettings());
        }
        return context.toString();
    }

    private List<WorkflowAiMessage> effectiveHistory(List<WorkflowAiMessage> history) {
        Set<UUID> supersededMessageIds = new HashSet<>();
        for (WorkflowAiMessage message : history) {
            if (message.getRegeneratedFromMessage() != null
                    && message.getRegeneratedFromMessage().getId() != null) {
                supersededMessageIds.add(message.getRegeneratedFromMessage().getId());
            }
        }
        return history.stream()
                .filter(message -> message.getRole() == WorkflowAiMessageRole.USER
                        || message.getRole() == WorkflowAiMessageRole.ASSISTANT)
                .filter(message -> message.getId() == null
                        || !supersededMessageIds.contains(message.getId()))
                .toList();
    }

    private String exactSourceIdentifiers(List<WorkflowAiMessage> history) {
        Set<String> identifiers = new LinkedHashSet<>();
        int totalCharacters = 0;
        for (WorkflowAiMessage message : history) {
            Matcher matcher = DISTINCTIVE_IDENTIFIER.matcher(message.getContent());
            while (matcher.find() && identifiers.size() < 64) {
                String identifier = matcher.group();
                if (identifier.length() <= 120
                        && !identifiers.contains(identifier)
                        && totalCharacters + identifier.length() + 2 <= 2000) {
                    identifiers.add(identifier);
                    totalCharacters += identifier.length() + 2;
                }
            }
        }
        return identifiers.isEmpty() ? null : String.join(", ", identifiers);
    }

    private ContextWindow compactContextIfNeeded(
            WorkflowAiConversation conversation,
            List<WorkflowAiMessage> history,
            ChatLanguageModel model,
            int fixedContextTokens,
            boolean persistSummary
    ) {
        String existingSummary = optionalText(conversation.getConversationSummary());
        int unsummarizedStart = summarizedHistoryEnd(
                history,
                existingSummary,
                conversation.getSummarizedThroughMessageId()
        );
        if (unsummarizedStart < 0) {
            existingSummary = null;
            unsummarizedStart = 0;
        }
        if (existingSummary != null
                && unsummarizedStart > 0
                && !summaryIsGrounded(
                        existingSummary,
                        history.subList(0, unsummarizedStart)
                )) {
            // Legacy/free-form summaries can be fluent but unrelated to the source. Because all
            // messages remain stored, discard the bad summary and rebuild from source anchors.
            log.warn("Discarding ungrounded workflow conversation summary and rebuilding it");
            existingSummary = null;
            unsummarizedStart = 0;
        }

        int estimatedTotal = fixedContextTokens + estimatedTokens(existingSummary);
        for (int index = unsummarizedStart; index < history.size(); index++) {
            estimatedTotal += estimatedTokens(history.get(index));
        }

        int contextLimit = Math.max(256, maximumContextTokens);
        if (estimatedTotal <= contextLimit) {
            return new ContextWindow(
                    existingSummary,
                    List.copyOf(history.subList(unsummarizedStart, history.size()))
            );
        }

        int recentLimit = Math.max(
                64,
                Math.min(recentContextTokens, Math.max(64, contextLimit / 2))
        );
        int retainFrom = history.size();
        int retainedTokens = 0;
        while (retainFrom > unsummarizedStart) {
            int nextTokens = estimatedTokens(history.get(retainFrom - 1));
            if (retainFrom < history.size()
                    && retainedTokens + nextTokens > recentLimit) {
                break;
            }
            retainFrom--;
            retainedTokens += nextTokens;
        }

        if (retainFrom <= unsummarizedStart) {
            // There is no older prefix to replace; the durable ASL or latest turn itself is large.
            return new ContextWindow(
                    existingSummary,
                    List.copyOf(history.subList(unsummarizedStart, history.size()))
            );
        }

        // Rebuild the durable summary from every older source turn. This prevents repeated model
        // compaction from gradually dropping identifiers and decisions that are still in the DB.
        List<WorkflowAiMessage> messagesToSummarize = List.copyOf(
                history.subList(0, retainFrom)
        );
        String nextSummary = summarizeHistory(
                model,
                null,
                messagesToSummarize,
                contextLimit
        );

        WorkflowAiMessage summarizedThrough = messagesToSummarize.get(
                messagesToSummarize.size() - 1
        );
        if (persistSummary && summarizedThrough.getId() != null) {
            conversation.setConversationSummary(nextSummary);
            conversation.setSummarizedThroughMessageId(summarizedThrough.getId());
            conversationRepository.save(conversation);
        }

        return new ContextWindow(
                nextSummary,
                List.copyOf(history.subList(retainFrom, history.size()))
        );
    }

    private int summarizedHistoryEnd(
            List<WorkflowAiMessage> history,
            String summary,
            UUID summarizedThroughMessageId
    ) {
        if (summary == null || summarizedThroughMessageId == null) {
            return summary == null ? 0 : -1;
        }
        for (int index = 0; index < history.size(); index++) {
            if (summarizedThroughMessageId.equals(history.get(index).getId())) {
                return index + 1;
            }
        }
        return -1;
    }

    private String summarizeHistory(
            ChatLanguageModel model,
            String existingSummary,
            List<WorkflowAiMessage> messages,
            int contextLimit
    ) {
        int targetTokens = Math.max(128, Math.min(1500, contextLimit / 4));
        StringBuilder source = new StringBuilder()
                .append("Keep the summary under approximately ")
                .append(targetTokens)
                .append(" tokens.\n");
        if (existingSummary != null) {
            source.append("\nExisting durable summary:\n")
                    .append(existingSummary)
                    .append('\n');
        }
        int sourceBudget = Math.max(
                2048,
                Math.min(maximumSummaryCharacters * 2, contextLimit * 3)
        );
        source.append("\nSource-anchored older turns to summarize:\n")
                .append(summarySourceAnchors(messages, sourceBudget));

        try {
            Response<AiMessage> summaryResponse = model.generate(List.of(
                    SystemMessage.systemMessage(SUMMARY_SYSTEM_PROMPT),
                    UserMessage.userMessage(source.toString())
            ));
            String generatedSummary = summaryResponse == null
                    || summaryResponse.content() == null
                    ? null
                    : summaryResponse.content().text();
            if (generatedSummary == null || generatedSummary.isBlank()) {
                throw new IllegalStateException("Summary model returned an empty reply");
            }
            String cleanedSummary = extractThinking(generatedSummary.trim()).answer();
            if (!summaryIsGrounded(cleanedSummary, messages)) {
                log.warn("Summary model omitted all distinctive source identifiers; using fallback");
                return fallbackSummary(existingSummary, messages);
            }
            return anchoredSummary(existingSummary, cleanedSummary, messages);
        } catch (RuntimeException exception) {
            // A summary refresh should not make the user's actual turn unavailable. The bounded
            // deterministic fallback keeps the context usable and the next refresh can improve it.
            log.warn("Could not generate workflow conversation summary; using bounded fallback", exception);
            return fallbackSummary(existingSummary, messages);
        }
    }

    private String fallbackSummary(
            String existingSummary,
            List<WorkflowAiMessage> messages
    ) {
        StringBuilder summary = new StringBuilder();
        if (existingSummary != null) {
            summary.append(existingSummary).append('\n');
        }
        summary.append("Source anchors (authoritative excerpts):\n")
                .append(summarySourceAnchors(
                        messages,
                        Math.max(256, maximumSummaryCharacters - summary.length() - 64)
                ));
        return limitSummary(summary.toString().trim());
    }

    private String anchoredSummary(
            String existingSummary,
            String generatedSummary,
            List<WorkflowAiMessage> messages
    ) {
        int limit = Math.max(512, maximumSummaryCharacters);
        StringBuilder summary = new StringBuilder();
        if (existingSummary != null) {
            summary.append(boundedExcerpt(existingSummary, limit / 4)).append('\n');
        }
        summary.append("Factual summary:\n")
                .append(boundedExcerpt(generatedSummary, limit / 3))
                .append("\nSource anchors (authoritative excerpts):\n");
        int remaining = Math.max(256, limit - summary.length());
        summary.append(summarySourceAnchors(messages, remaining));
        return limitSummary(summary.toString().trim());
    }

    private String summarySourceAnchors(
            List<WorkflowAiMessage> messages,
            int characterBudget
    ) {
        if (messages.isEmpty()) {
            return "";
        }
        int budget = Math.max(128, characterBudget);
        int perMessage = Math.max(32, (budget / messages.size()) - 16);
        StringBuilder anchors = new StringBuilder();
        for (WorkflowAiMessage message : messages) {
            anchors.append(message.getRole())
                    .append(": ")
                    .append(boundedExcerpt(compactSummaryText(message.getContent()), perMessage))
                    .append('\n');
        }
        return anchors.length() <= budget
                ? anchors.toString().trim()
                : boundedExcerpt(anchors.toString().trim(), budget);
    }

    private String compactSummaryText(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return REPEATED_CHARACTER_RUN.matcher(compact).replaceAll("$1…");
    }

    private String boundedExcerpt(String value, int limit) {
        String compact = value == null ? "" : value.trim();
        int boundedLimit = Math.max(24, limit);
        if (compact.length() <= boundedLimit) {
            return compact;
        }
        int headLength = (boundedLimit * 2) / 3;
        int tailLength = Math.max(1, boundedLimit - headLength - 5);
        return compact.substring(0, headLength)
                + " ... "
                + compact.substring(compact.length() - tailLength);
    }

    private boolean summaryIsGrounded(
            String summary,
            List<WorkflowAiMessage> sourceMessages
    ) {
        Set<String> identifiers = new HashSet<>();
        for (WorkflowAiMessage message : sourceMessages) {
            Matcher matcher = DISTINCTIVE_IDENTIFIER.matcher(message.getContent());
            while (matcher.find() && identifiers.size() < 64) {
                identifiers.add(matcher.group().toLowerCase(Locale.ROOT));
            }
        }
        if (identifiers.isEmpty()) {
            return true;
        }
        String normalizedSummary = summary == null
                ? ""
                : summary.toLowerCase(Locale.ROOT);
        return identifiers.stream().anyMatch(normalizedSummary::contains);
    }

    private String limitSummary(String value) {
        String summary = value == null ? "" : value.trim();
        int limit = Math.max(512, maximumSummaryCharacters);
        if (summary.length() <= limit) {
            return summary;
        }
        int headLength = (limit * 2) / 3;
        int tailLength = limit - headLength;
        return summary.substring(0, headLength)
                + "\n...[older summary compacted]...\n"
                + summary.substring(summary.length() - tailLength);
    }

    private int estimatedTokens(WorkflowAiMessage message) {
        return 6 + estimatedTokens(message.getContent());
    }

    private int estimatedTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return 4 + (value.length() + APPROXIMATE_CHARACTERS_PER_TOKEN - 1)
                / APPROXIMATE_CHARACTERS_PER_TOKEN;
    }

    private ParsedAssistantResponse parseAssistantResponse(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Response root must be a JSON object");
            }
            if (looksLikeAsl(root)) {
                return new ParsedAssistantResponse(
                        WorkflowAiConversationStage.ASL_READY,
                        "ASL definition is ready.",
                        root,
                        null,
                        null,
                        null,
                        true,
                        null
                );
            }
            WorkflowAiConversationStage stage = parseStage(root.path("stage"));
            String message = root.path("message").isString()
                    ? root.path("message").stringValue()
                    : null;
            if (stage == null || message == null || message.isBlank()) {
                throw new IllegalArgumentException(
                        "Response must include a recognized stage and non-empty message"
                );
            }
            JsonNode asl = root.get("aslDefinition");
            JsonNode finalPlan = root.get("finalPlan");
            JsonNode draftPayloadNode = root.get("draftWorkflowPayload");
            CreateWorkflowRequestDTO draftPayload = draftPayloadNode == null
                    || draftPayloadNode.isNull()
                    ? null
                    : objectMapper.treeToValue(
                            draftPayloadNode,
                            CreateWorkflowRequestDTO.class
                    );
            return new ParsedAssistantResponse(
                    stage,
                    message,
                    asl,
                    finalPlan,
                    draftPayload,
                    draftPayloadNode,
                    true,
                    null
            );
        } catch (Exception exception) {
            log.warn("Could not parse workflow AI response as structured JSON", exception);
            return new ParsedAssistantResponse(
                    null,
                    value,
                    null,
                    null,
                    null,
                    null,
                    false,
                    exception.getMessage()
            );
        }
    }

    private List<String> validateExecutableDefinition(JsonNode definition) {
        AslValidationResult validation = aslDefinitionValidator.validate(definition);
        List<AslValidationIssue> issues = new ArrayList<>(validation.issues());
        issues.addAll(runtimeCapabilityValidator.validate(definition));
        return issues.stream()
                .map(issue -> String.format(
                        "[%s] %s at %s: %s",
                        issue.category(),
                        issue.code(),
                        issue.location(),
                        issue.message()
                ))
                .toList();
    }

    private WorkflowAiConversation findForUpdate(UUID conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("Conversation id cannot be null");
        }
        return conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow AI conversation does not exist"
                ));
    }

    private void appendMessage(
            WorkflowAiConversation conversation,
            WorkflowAiMessageRole role,
            String content,
            String structuredPayload,
            AiModelConfig modelConfig
    ) {
        appendMessage(
                conversation,
                role,
                content,
                structuredPayload,
                modelConfig,
                null,
                null,
                null,
                null,
                null
        );
    }

    private WorkflowAiMessage appendMessage(
            WorkflowAiConversation conversation,
            WorkflowAiMessageRole role,
            String content,
            String structuredPayload,
            AiModelConfig modelConfig,
            String thinkingContent,
            Long durationMs,
            TokenUsage tokenUsage,
            String finishReason,
            WorkflowAiMessage regeneratedFromMessage
    ) {
        WorkflowAiMessage message = new WorkflowAiMessage();
        message.setConversation(conversation);
        message.setModelConfig(modelConfig);
        message.setRegeneratedFromMessage(regeneratedFromMessage);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setStructuredPayload(structuredPayload);
        message.setThinkingContent(optionalText(thinkingContent));
        message.setDurationMs(durationMs);
        if (tokenUsage != null) {
            message.setInputTokens(tokenUsage.inputTokenCount());
            message.setOutputTokens(tokenUsage.outputTokenCount());
            message.setTotalTokens(tokenUsage.totalTokenCount());
        }
        message.setFinishReason(finishReason);
        return messageRepository.saveAndFlush(message);
    }

    private WorkflowAiResponseDTO response(
            WorkflowAiConversation conversation,
            String message,
            JsonNode aslDefinition,
            List<String> validationIssues,
            JsonNode finalPlan,
            CreateWorkflowRequestDTO draftWorkflowPayload,
            WorkflowResponseDTO workflow,
            WorkflowAiMessageDTO assistantMessage
    ) {
        return new WorkflowAiResponseDTO(
                conversation.getId(),
                conversation.getName(),
                conversation.getStage(),
                message,
                aslDefinition,
                validationIssues == null ? List.of() : validationIssues,
                finalPlan,
                draftWorkflowPayload,
                workflow == null ? null : workflow.id(),
                workflow,
                assistantMessage
        );
    }

    private WorkflowAiConversationSummaryDTO summary(
            WorkflowAiConversation conversation
    ) {
        return new WorkflowAiConversationSummaryDTO(
                conversation.getId(),
                conversation.getName(),
                conversation.getStage(),
                conversation.getModelConfig().getId(),
                conversation.getModelConfig().getDisplayName(),
                conversation.getInitialInstruction(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private WorkflowAiConversationDetailDTO detail(
            WorkflowAiConversation conversation
    ) {
        return new WorkflowAiConversationDetailDTO(
                conversation.getId(),
                conversation.getName(),
                conversation.getStage(),
                conversation.getModelConfig().getId(),
                conversation.getModelConfig().getDisplayName(),
                conversation.getInitialInstruction(),
                readJson(conversation.getDraftAsl()),
                readJson(conversation.getFinalPlan()),
                readDraftPayload(conversation),
                readJson(conversation.getCanvasLayout()),
                readWorkspaceSettings(conversation),
                messageRepository.findByConversationOrderByCreatedAtAsc(conversation)
                        .stream()
                        .map(this::message)
                        .toList(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private WorkflowAiMessageDTO message(WorkflowAiMessage message) {
        return new WorkflowAiMessageDTO(
                message.getId(),
                message.getRole(),
                userFacingMessageContent(message),
                message.getModelConfig() == null ? null : message.getModelConfig().getId(),
                message.getModelConfig() == null ? null : message.getModelConfig().getDisplayName(),
                message.getDurationMs(),
                message.getInputTokens(),
                message.getOutputTokens(),
                message.getTotalTokens(),
                message.getThinkingContent(),
                message.getFinishReason(),
                message.getRegeneratedFromMessage() == null
                        ? null
                        : message.getRegeneratedFromMessage().getId(),
                message.getCreatedAt()
        );
    }

    /** Walks back to the original assistant reply that later retries all descend from. */
    private WorkflowAiMessage regenerationRoot(WorkflowAiMessage message) {
        WorkflowAiMessage root = message;
        Set<UUID> visited = new HashSet<>();
        while (visited.add(root.getId()) && root.getRegeneratedFromMessage() != null) {
            root = root.getRegeneratedFromMessage();
        }
        return root;
    }

    private List<WorkflowAiMessage> historyBeforeMessage(
            WorkflowAiConversation conversation,
            WorkflowAiMessage targetMessage
    ) {
        List<WorkflowAiMessage> history = new ArrayList<>();
        for (WorkflowAiMessage message :
                messageRepository.findByConversationOrderByCreatedAtAsc(conversation)) {
            if (message.getId().equals(targetMessage.getId())) {
                break;
            }
            history.add(message);
        }
        return history;
    }

    private CreateWorkflowRequestDTO fallbackDraftPayload(
            WorkflowAiConversation conversation
    ) {
        JsonNode definition = readJson(conversation.getDraftAsl());
        if (definition == null) {
            throw new IllegalStateException("No reviewed ASL definition is available");
        }
        return new CreateWorkflowRequestDTO(
                conversation.getName(),
                null,
                3,
                "workflow-ai-" + conversation.getId(),
                "UTC",
                definition
        );
    }

    private CreateWorkflowRequestDTO readDraftPayload(
            WorkflowAiConversation conversation
    ) {
        if (conversation.getDraftWorkflowPayload() == null) {
            return null;
        }
        try {
            return objectMapper.treeToValue(
                    objectMapper.readTree(conversation.getDraftWorkflowPayload()),
                    CreateWorkflowRequestDTO.class
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read stored draft workflow payload",
                    exception
            );
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read stored JSON", exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize value", exception);
        }
    }

    private WorkflowAiConversationStage parseStage(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return WorkflowAiConversationStage.valueOf(
                    node.stringValue().trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean looksLikeAsl(JsonNode root) {
        return root != null && root.isObject() && root.has("StartAt") && root.has("States");
    }

    private String stripMarkdown(String rawOutput) {
        String output = rawOutput == null ? "" : rawOutput.trim();
        if (output.startsWith("```json")) {
            output = output.substring(7);
        }
        if (output.startsWith("```")) {
            output = output.substring(3);
        }
        if (output.endsWith("```")) {
            output = output.substring(0, output.length() - 3);
        }
        return output.trim();
    }

    private ThinkingExtraction extractThinking(String value) {
        String output = value == null ? "" : value;
        Matcher matcher = THINKING_PATTERN.matcher(output);
        StringBuilder thinking = new StringBuilder();
        while (matcher.find()) {
            String block = matcher.group(1);
            if (block != null && !block.isBlank()) {
                if (!thinking.isEmpty()) {
                    thinking.append("\n\n");
                }
                thinking.append(block.trim());
            }
        }
        String answer = matcher.replaceAll("").trim();
        return new ThinkingExtraction(
                answer.isBlank() ? output.trim() : answer,
                thinking.isEmpty() ? null : thinking.toString()
        );
    }

    private String generateConversationName(String instruction) {
        String compact = instruction.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 48) {
            return compact;
        }
        return compact.substring(0, 48).trim();
    }

    private String jsonPayloadOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            objectMapper.readTree(value);
            return value;
        } catch (Exception exception) {
            // structured_payload is a json column, so malformed model output would fail the whole
            // insert and lose the turn. The prose is kept on the message content regardless.
            log.debug("Assistant response was not valid JSON; storing no structured payload", exception);
            return null;
        }
    }

    private String editorAslContext(JsonNode definition) {
        if (!looksLikeAsl(definition) || definition.path("States").isEmpty()) {
            return null;
        }
        return "Current ASL in the user's editor:\n" + serialize(definition);
    }

    private String rejectedEditorCandidateContext(
            JsonNode definition,
            List<String> validationIssues
    ) {
        return "Candidate ASL in the user's editor (not authoritative):\n"
                + serialize(definition)
                + "\nValidator issues:\n"
                + String.join("\n", validationIssues);
    }

    private WorkflowAiWorkspaceSettingsDTO readWorkspaceSettings(
            WorkflowAiConversation conversation
    ) {
        if (conversation.getWorkspaceSettings() == null) {
            return null;
        }
        try {
            return objectMapper.treeToValue(
                    objectMapper.readTree(conversation.getWorkspaceSettings()),
                    WorkflowAiWorkspaceSettingsDTO.class
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read stored workflow AI workspace settings",
                    exception
            );
        }
    }

    private String userFacingMessageContent(WorkflowAiMessage message) {
        String content = message.getContent();
        if (message.getRole() != WorkflowAiMessageRole.USER || content == null) {
            return content;
        }

        // Older conversations stored model-only context in the user message. Keep that context in
        // model history, but do not expose it as text the user typed when the chat is rehydrated.
        int editorContextStart = content.indexOf("\n\nCurrent ASL in the user's editor:\n");
        if (editorContextStart >= 0) {
            content = content.substring(0, editorContextStart);
        }
        return content.replaceFirst(
                "(?s)\\n\\nUser date/time context:\\s*[^\\r\\n]+\\s*$",
                ""
        );
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return value.trim();
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ParsedAssistantResponse(
            WorkflowAiConversationStage stage,
            String message,
            JsonNode aslDefinition,
            JsonNode finalPlan,
            CreateWorkflowRequestDTO draftWorkflowPayload,
            JsonNode draftWorkflowPayloadNode,
            boolean structured,
            String failureReason
    ) {
        private boolean hasDefinitionCandidate() {
            return aslDefinition != null
                    || draftWorkflowPayload != null
                    && draftWorkflowPayload.definition() != null;
        }
    }

    private record AssistantAttempt(
            Response<AiMessage> modelResponse,
            String cleaned,
            ThinkingExtraction thinkingExtraction,
            ParsedAssistantResponse parsed
    ) {
    }

    private record ThinkingExtraction(
            String answer,
            String thinkingContent
    ) {
    }

    private record ContextWindow(
            String summary,
            List<WorkflowAiMessage> recentMessages
    ) {
    }
}
